package dev.nibin.buzzer.scoring.infrastructure.kafka;

import dev.nibin.buzzer.events.AnswerSubmitted;
import dev.nibin.buzzer.events.EventHeaders;
import dev.nibin.buzzer.events.SessionEnded;
import dev.nibin.buzzer.events.SessionLifecycle;
import dev.nibin.buzzer.events.SessionStarted;
import dev.nibin.buzzer.scoring.RedisTestcontainer;
import dev.nibin.buzzer.scoring.RedpandaTestcontainer;
import dev.nibin.buzzer.scoring.TestcontainersConfiguration;
import dev.nibin.buzzer.scoring.application.LogContext;
import dev.nibin.buzzer.scoring.infrastructure.persistence.JdbcScoringRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupListing;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * The consumer end to end: records are produced to real Redpanda topics exactly as session-service's outbox does
 * (key = sessionId, eventType header, JSON value), and the results are read from real Postgres. Each test uses its
 * own session id, so its records share one partition and are read in the order they were sent.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, RedisTestcontainer.class, RedpandaTestcontainer.class})
@ExtendWith(OutputCaptureExtension.class)
class ScoringEventListenerTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final String GROUP = "scoring-service";

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private JsonMapper json;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private MeterRegistry meters;

    @Autowired
    private LoggingSystem loggingSystem;

    @MockitoSpyBean
    private JdbcScoringRepository scoring;

    @MockitoSpyBean
    private EventReader reader;

    @MockitoSpyBean
    private KafkaScoreUpdatePublisher publisher;

    private final UUID session = UUID.randomUUID();

    @Test
    void aRedeliveredAnswerIsScoredOnce() throws Exception {
        UUID ada = UUID.randomUUID();
        AnswerSubmitted first = answer(ada, true, 1);
        AnswerSubmitted marker = answer(ada, false, 0);
        double applied = events("applied");
        double duplicates = events("duplicate");
        long delays = meters.get(ScoringEventListener.DELAY).timer().count();

        send(first);
        send(first); // the same eventId again: what an outbox resend or a consumer redelivery looks like
        send(marker); // same key, same partition: read after both copies

        await().atMost(WAIT).until(() -> processed(marker.eventId()));
        assertThat(score(ada)).contains(new Score(1000, 2, 1)); // 1000 + 0, from 2 answers, not 3
        verify(scoring, times(2)).markProcessed(first.eventId()); // the copy was read, and skipped
        // buzzer.scoring.events and buzzer.scoring.delay: 2 applied and timed, 1 skipped as a duplicate (not timed).
        // Counted just after each transaction returns, a moment after processed_events shows it: await.
        await().atMost(WAIT).until(() -> events("applied") == applied + 2);
        assertThat(events("duplicate")).isEqualTo(duplicates + 1);
        assertThat(meters.get(ScoringEventListener.DELAY).timer().count()).isEqualTo(delays + 2);
    }

    /**
     * Lag comes from the Kafka client itself; Boot's KafkaMetricsAutoConfiguration binds its metrics to Micrometer.
     * Per consumer at once: records.lag.max, one series per client (main, retry-1000, retry-2000, dlt). Per partition
     * (records.lag{topic, partition}) only later: Kafka creates those on the first fetch from a partition, and
     * Micrometer looks for new client metrics every 60 s, so they appear up to a minute after the consumer starts.
     * Not waited for here. Either way they are reported BY the consumer: if scoring-service is down, they vanish
     * instead of rising.
     */
    @Test
    void eachConsumerReportsItsMaximumLagAndTheMainOneIsAmongThem() {
        assertThat(meters.find("kafka.consumer.fetch.manager.records.lag.max").gauges())
                .extracting(gauge -> gauge.getId().getTag("client.id"))
                .anyMatch(clientId -> clientId.matches("consumer-scoring-service-\\d+")) // the main group
                .anyMatch(clientId -> clientId.startsWith("consumer-scoring-service-dlt-"));
    }

    @Test
    void aPoisonRecordSkipsTheRetriesReachesTheDltIsCountedAndItsOffsetMovesOn() throws Exception {
        String dlt = AnswerSubmitted.TOPIC + "-dlt";
        double before = deadLettered(dlt);

        RecordMetadata poison = send(AnswerSubmitted.TOPIC, "AnswerSubmitted", "{\"eventId\": ");

        await().atMost(WAIT).until(() -> deadLettered(dlt) == before + 1);
        // Read once, on the main topic: never on a retry topic (3 reads if UnreadableEventException were retried).
        verify(reader, times(1)).read(argThat(record -> session.toString().equals(record.key())));
        // Acked on the main topic, so a restart won't read it again.
        TopicPartition partition = new TopicPartition(AnswerSubmitted.TOPIC, poison.partition());
        await().atMost(WAIT).until(() -> committedOffset(partition) > poison.offset());
        // The headers ScoringEventListener.onDeadLetter logs: the retry-topic path writes kafka_original-* and
        // kafka_exception-*, not the kafka_dlt-* names.
        ConsumerRecord<String, String> dead = readOne(dlt);
        assertThat(header(dead, KafkaHeaders.ORIGINAL_TOPIC)).isEqualTo(AnswerSubmitted.TOPIC);
        assertThat(header(dead, KafkaHeaders.EXCEPTION_CAUSE_FQCN)).isEqualTo(UnreadableEventException.class.getName());
        assertThat(header(dead, EventHeaders.TYPE)).isEqualTo("AnswerSubmitted");
    }

    /**
     * The line about a skipped redelivery (DEBUG, switched on for this test only) carries the session, taken from the
     * record's key, and the player, taken from the event.
     */
    @Test
    void theLineAboutARedeliveryCarriesItsSessionAndPlayer(CapturedOutput output) throws Exception {
        UUID ada = UUID.randomUUID();
        AnswerSubmitted answer = answer(ada, true, 1);
        AnswerSubmitted marker = answer(ada, false, 0);

        loggingSystem.setLogLevel(ScoringEventListener.class.getName(), LogLevel.DEBUG);
        try {
            send(answer);
            send(answer);
            send(marker); // same key, same partition: once it's processed, the copy before it has been skipped
            await().atMost(WAIT).until(() -> processed(marker.eventId()));
        } finally {
            loggingSystem.setLogLevel(ScoringEventListener.class.getName(), null);
        }

        JsonNode line = logLines(output)
                .filter(json -> json.path("message").asString().startsWith("Already applied, skipped"))
                .filter(json -> json.path(LogContext.SESSION_ID).asString().equals(session.toString()))
                .findFirst().orElseThrow();
        assertThat(line.path(LogContext.PLAYER_ID).asString()).isEqualTo(ada.toString());
    }

    /** A record that can't even be parsed still names its session on the DLT, from the key that survives retries. */
    @Test
    void theDeadLetterLineCarriesTheSessionFromTheKey(CapturedOutput output) throws Exception {
        String dlt = AnswerSubmitted.TOPIC + "-dlt";
        double before = deadLettered(dlt);

        send(AnswerSubmitted.TOPIC, "AnswerSubmitted", "{\"eventId\": ");
        await().atMost(WAIT).until(() -> deadLettered(dlt) == before + 1);

        JsonNode line = logLines(output)
                .filter(json -> json.path("message").asString().startsWith("Dead-lettered"))
                .filter(json -> json.path(LogContext.SESSION_ID).asString().equals(session.toString()))
                .findFirst().orElseThrow();
        assertThat(line.path("log").path("level").asString()).isEqualTo("ERROR");
        assertThat(line.has(LogContext.PLAYER_ID)).isFalse(); // never parsed, so no player
    }

    @Test
    void eachRetryTopicAndTheDltIsReadByAConsumerGroupOfItsOwn() throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            await().atMost(WAIT).untilAsserted(() -> assertThat(admin.listConsumerGroups().all()
                    .get(10, TimeUnit.SECONDS).stream().map(ConsumerGroupListing::groupId))
                    .contains(GROUP, GROUP + "-retry-1000", GROUP + "-retry-2000", GROUP + "-dlt"));
        }
    }

    @Test
    void anAnswerWhoseWriteFailsOnceIsScoredOnItsRetryExactlyOnce() throws Exception {
        UUID bob = UUID.randomUUID();
        doThrow(new TransientDataAccessResourceException("Postgres blip")).doCallRealMethod()
                .when(scoring).addAnswer(eq(session), eq(bob), anyInt(), anyBoolean());
        AnswerSubmitted answer = answer(bob, true, 2);

        send(answer);

        await().atMost(WAIT).until(() -> processed(answer.eventId()));
        assertThat(score(bob)).contains(new Score(900, 1, 1));
        verify(scoring, times(2)).addAnswer(eq(session), eq(bob), anyInt(), anyBoolean());
        // Marked twice: the first mark rolled back together with the failed write, so the retry wasn't skipped.
        verify(scoring, times(2)).markProcessed(answer.eventId());
        // Read on the main topic, then on the first retry topic.
        verify(reader).read(argThat(record -> AnswerSubmitted.TOPIC.equals(record.topic())
                && session.toString().equals(record.key())));
        verify(reader).read(argThat(record -> (AnswerSubmitted.TOPIC + "-retry-1000").equals(record.topic())
                && session.toString().equals(record.key())));
    }

    @Test
    void aScoreUpdateThatFailsToPublishIsSentAgainFromTheRetryTopicWhileTheScoreCountsOnce() throws Exception {
        UUID dan = UUID.randomUUID();
        doThrow(new IllegalStateException("broker down")).doCallRealMethod()
                .when(publisher).publish(argThat(update -> session.equals(update.sessionId())));
        AnswerSubmitted answer = answer(dan, true, 1);

        send(answer); // scored and committed, then the publish fails: the record goes to -retry-1000

        verify(publisher, timeout(WAIT.toMillis()).times(2))
                .publish(argThat(update -> session.equals(update.sessionId())));
        assertThat(score(dan)).contains(new Score(1000, 1, 1)); // the retry skipped the score...
        verify(scoring, times(2)).markProcessed(answer.eventId()); // ...because it was already processed
        assertThat(jdbc.sql("SELECT version FROM scoring_sessions WHERE session_id = :session")
                .param("session", session).query(Long.class).single()).isEqualTo(1); // bumped once, not twice
    }

    @Test
    void anAnswerBeforeItsSessionStartsStillCountsAndTheSessionEnds() throws Exception {
        UUID cid = UUID.randomUUID();
        AnswerSubmitted answer = answer(cid, true, 3);
        SessionStarted started = new SessionStarted(UUID.randomUUID(), session, UUID.randomUUID(),
                UUID.randomUUID(), 5, 1_000L, SessionStarted.SCHEMA_VERSION);
        SessionEnded ended = new SessionEnded(UUID.randomUUID(), session, 2_000L, SessionEnded.SCHEMA_VERSION);

        send(answer); // different topics: nothing orders the answer after the start
        send(SessionLifecycle.TOPIC, "SessionStarted", json.writeValueAsString(started));
        send(SessionLifecycle.TOPIC, "SessionEnded", json.writeValueAsString(ended));

        await().atMost(WAIT).until(() -> processed(answer.eventId()) && processed(ended.eventId()));
        assertThat(sessionRow()).contains(new SessionRow(5, 1_000L, 2_000L));
        assertThat(score(cid)).contains(new Score(800, 1, 1));
    }

    @Test
    void anEndReadBeforeItsStartKeepsBoth() throws Exception {
        SessionEnded ended = new SessionEnded(UUID.randomUUID(), session, 2_000L, SessionEnded.SCHEMA_VERSION);
        SessionStarted started = new SessionStarted(UUID.randomUUID(), session, UUID.randomUUID(),
                UUID.randomUUID(), 3, 1_000L, SessionStarted.SCHEMA_VERSION);

        send(SessionLifecycle.TOPIC, "SessionEnded", json.writeValueAsString(ended)); // as after a retry
        send(SessionLifecycle.TOPIC, "SessionStarted", json.writeValueAsString(started));

        await().atMost(WAIT).until(() -> processed(started.eventId()));
        assertThat(sessionRow()).contains(new SessionRow(3, 1_000L, 2_000L));
    }

    private AnswerSubmitted answer(UUID player, boolean correct, int correctRank) {
        return new AnswerSubmitted(UUID.randomUUID(), session, UUID.randomUUID(), player, 0, correct, correctRank,
                1, 1_000L, AnswerSubmitted.SCHEMA_VERSION);
    }

    private void send(AnswerSubmitted answer) throws Exception {
        send(AnswerSubmitted.TOPIC, "AnswerSubmitted", json.writeValueAsString(answer));
    }

    private RecordMetadata send(String topic, String type, String value) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, session.toString(), value);
        record.headers().add(EventHeaders.TYPE, type.getBytes(StandardCharsets.UTF_8));
        return kafka.send(record).get(10, TimeUnit.SECONDS).getRecordMetadata();
    }

    /** The captured JSON log lines (application.yml: ECS on the console); anything else is skipped. */
    private Stream<JsonNode> logLines(CapturedOutput output) {
        return output.getOut().lines().filter(line -> line.startsWith("{")).map(json::readTree);
    }

    private double events(String result) {
        return meters.get(ScoringEventListener.EVENTS).tag("type", "AnswerSubmitted").tag("result", result).counter()
                .count();
    }

    private boolean processed(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM processed_events WHERE event_id = :id")
                .param("id", eventId).query(Long.class).single() == 1;
    }

    private Optional<Score> score(UUID player) {
        return jdbc.sql("""
                        SELECT points, answers, correct_answers FROM player_scores
                        WHERE session_id = :session AND player_id = :player
                        """)
                .param("session", session).param("player", player)
                .query(Score.class).optional();
    }

    private Optional<SessionRow> sessionRow() {
        return jdbc.sql("""
                        SELECT question_count, started_at_ms, ended_at_ms FROM scoring_sessions
                        WHERE session_id = :session
                        """)
                .param("session", session)
                .query(SessionRow.class).optional();
    }

    private double deadLettered(String topic) {
        Counter counter = meters.find(ScoringEventListener.DEAD_LETTERED).tag("topic", topic).counter();
        return counter == null ? 0 : counter.count();
    }

    private long committedOffset(TopicPartition partition) throws Exception {
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets(GROUP)
                    .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS).get(partition);
            return committed == null ? -1 : committed.offset();
        }
    }

    /** This test's record on a topic, read from the beginning by a throwaway consumer group. */
    private ConsumerRecord<String, String> readOne(String topic) {
        Map<String, Object> config = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (session.toString().equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        throw new AssertionError("no record keyed " + session + " on " + topic);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    record Score(int points, int answers, int correctAnswers) {
    }

    record SessionRow(Integer questionCount, Long startedAtMs, Long endedAtMs) {
    }
}
