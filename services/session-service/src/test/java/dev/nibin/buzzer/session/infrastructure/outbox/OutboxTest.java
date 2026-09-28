package dev.nibin.buzzer.session.infrastructure.outbox;

import dev.nibin.buzzer.events.AnswerSubmitted;
import dev.nibin.buzzer.events.EventHeaders;
import dev.nibin.buzzer.events.SessionEnded;
import dev.nibin.buzzer.events.SessionLifecycle;
import dev.nibin.buzzer.session.ApiIntegrationTest;
import dev.nibin.buzzer.session.application.EventOutbox;
import dev.nibin.buzzer.session.application.JoinSession;
import dev.nibin.buzzer.session.application.RunSession;
import dev.nibin.buzzer.session.application.SubmitAnswer;
import dev.nibin.buzzer.session.config.OutboxConfig;
import dev.nibin.buzzer.session.domain.Answer;
import dev.nibin.buzzer.session.domain.AnswerRepository;
import dev.nibin.buzzer.session.domain.LiveStateRepository;
import dev.nibin.buzzer.session.domain.Player;
import dev.nibin.buzzer.session.domain.PlayerRepository;
import dev.nibin.buzzer.session.domain.RoomCode;
import dev.nibin.buzzer.session.domain.Session;
import dev.nibin.buzzer.session.domain.SessionQuestion;
import dev.nibin.buzzer.session.domain.SessionRepository;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionOperations;
import org.testcontainers.redpanda.RedpandaContainer;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The outbox end to end, against real Postgres, Redis and Redpanda: an answer (or a start and an end) goes through
 * the real use cases, OutboxPublisher picks it up on its own schedule, and a plain Kafka consumer reads the topic.
 * The broker is shared by every test in the context, so each test reads only the records keyed by its session.
 */
@ApiIntegrationTest
class OutboxTest {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration ARRIVAL_WAIT = Duration.ofSeconds(15);
    /** After the expected records arrived, keep reading this long: several publisher runs (300 ms apart). */
    private static final Duration DUPLICATE_WATCH = Duration.ofSeconds(3);

    @Autowired
    private SessionRepository sessions;

    @Autowired
    private LiveStateRepository liveState;

    @Autowired
    private JoinSession joinSession;

    @Autowired
    private PlayerRepository players;

    @Autowired
    private RunSession runSession;

    @Autowired
    private SubmitAnswer submitAnswer;

    @Autowired
    private AnswerRepository answers;

    @Autowired
    private EventOutbox outbox;

    @Autowired
    private OutboxJpaRepository outboxRows;

    @Autowired
    private TransactionOperations transaction;

    @Autowired
    private JsonMapper json;

    @Autowired
    private RedpandaContainer redpanda;

    @Autowired
    @Qualifier(OutboxConfig.OUTBOX_SCHEDULER)
    private ThreadPoolTaskScheduler outboxScheduler;

    private Session session;
    private UUID host;

    @BeforeEach
    void newSession() {
        host = UUID.randomUUID();
        session = Session.create(RoomCode.random(RANDOM), UUID.randomUUID(), host, "Capitals",
                List.of(new SessionQuestion(UUID.randomUUID(), "Capital of France?", 20, List.of("Paris", "Lyon"), 0),
                        new SessionQuestion(UUID.randomUUID(), "Capital of Italy?", 15, List.of("Milan", "Rome"), 1)),
                Instant.now());
        sessions.add(session);
        liveState.initialize(session.id(), session.status());
    }

    @Test
    void eachAcceptedAnswerReachesKafkaExactlyOnceInSeqOrder() throws Exception {
        UUID ada = join("Ada");
        UUID bob = join("Bob");
        UUID cid = join("Cid");
        runSession.start(session.id(), host);
        UUID question = session.questions().get(0).questionId();

        submitAnswer.submit(session.id(), ada, question, 1); // wrong
        submitAnswer.submit(session.id(), bob, question, 0); // right, first correct
        submitAnswer.submit(session.id(), cid, question, 0); // right, second correct
        submitAnswer.submit(session.id(), ada, question, 0); // a duplicate: no second event

        List<ConsumerRecord<String, String>> records = consume(AnswerSubmitted.TOPIC, 3);

        assertThat(records).hasSize(3); // exactly once each, even after several more publisher runs
        List<AnswerSubmitted> events = records.stream()
                .map(record -> json.readValue(record.value(), AnswerSubmitted.class)).toList();
        assertThat(events).extracting(AnswerSubmitted::seq).containsExactly(1L, 2L, 3L); // one key, one partition
        assertThat(events).extracting(AnswerSubmitted::correctRank).containsExactly(0, 1, 2);
        assertThat(records).allSatisfy(record -> assertThat(typeOf(record)).isEqualTo("AnswerSubmitted"));
        // The eventId is the recorded answer's id, and the row was marked sent.
        Answer bobsAnswer = answers.find(session.id(), question, playerIdOf(bob)).orElseThrow();
        assertThat(events.get(1)).isEqualTo(new AnswerSubmitted(bobsAnswer.answerId(), session.id(), question,
                bobsAnswer.playerId(), 0, true, 1, 2, bobsAnswer.answeredAt().toEpochMilli(), 1));
        assertThat(outboxRows.findByEventId(bobsAnswer.answerId()).orElseThrow().getSentAt()).isNotNull();
    }

    @Test
    void startAndEndArriveOnTheLifecycleTopicInOrder() throws Exception {
        runSession.start(session.id(), host);
        runSession.end(session.id(), host);

        List<ConsumerRecord<String, String>> records = consume(SessionLifecycle.TOPIC, 2);

        assertThat(records).extracting(OutboxTest::typeOf).containsExactly("SessionStarted", "SessionEnded");
        SessionEnded ended = json.readValue(records.get(1).value(), SessionEnded.class);
        assertThat(ended.sessionId()).isEqualTo(session.id());
    }

    @Test
    void theAnswerRowAndItsOutboxRowCommitOrRollBackTogether() {
        Player ada = joinSession.join(session.roomCode().value(), UUID.randomUUID(), "Ada").player();
        UUID question = session.questions().get(0).questionId();
        Answer answer = new Answer(UUID.randomUUID(), session.id(), question, ada.playerId(), 0, true, 1, 1,
                Instant.now());

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            answers.add(answer);
            outbox.answerSubmitted(new AnswerSubmitted(answer.answerId(), session.id(), question, ada.playerId(),
                    0, true, 1, 1, answer.answeredAt().toEpochMilli(), 1));
            throw new IllegalStateException("something after both writes fails");
        })).hasMessage("something after both writes fails");

        assertThat(answers.find(session.id(), question, ada.playerId())).isEmpty();
        assertThat(outboxRows.findByEventId(answer.answerId())).isEmpty();
    }

    @Test
    void theOutboxRefusesToWriteOutsideATransaction() {
        SessionEnded event = new SessionEnded(UUID.randomUUID(), session.id(), 0, 1);

        assertThatThrownBy(() -> outbox.sessionEnded(event)).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(outboxRows.findByEventId(event.eventId())).isEmpty();
    }

    @Test
    void thePublisherRunsOnItsOwnSchedulerNotTheStompBrokers() throws InterruptedException {
        // getTaskCount() counts executions, one per run of a periodic task. The publisher is the only task on this
        // pool, so a count that keeps rising means its runs happen here (every 300 ms), not on MessageBroker-*.
        ScheduledThreadPoolExecutor executor = outboxScheduler.getScheduledThreadPoolExecutor();
        long before = executor.getTaskCount();
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (executor.getTaskCount() <= before + 2 && System.nanoTime() < deadline) {
            Thread.sleep(100);
        }

        assertThat(executor.getTaskCount()).isGreaterThan(before + 2);
        assertThat(Thread.getAllStackTraces().keySet()).extracting(Thread::getName).contains("outbox-1");
    }

    // --- helpers ---

    /**
     * Reads the topic from the beginning with a fresh consumer group, keeping this session's records only: until
     * {@code expected} of them arrived, then for DUPLICATE_WATCH longer, so a record sent twice would show up.
     */
    private List<ConsumerRecord<String, String>> consume(String topic, int expected) {
        String key = session.id().toString();
        List<ConsumerRecord<String, String>> received = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, redpanda.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "outbox-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            pollInto(consumer, key, received, expected, ARRIVAL_WAIT);
            pollInto(consumer, key, received, Integer.MAX_VALUE, DUPLICATE_WATCH);
        }
        return received;
    }

    private static void pollInto(KafkaConsumer<String, String> consumer, String key,
            List<ConsumerRecord<String, String>> received, int stopAt, Duration within) {
        long deadline = System.nanoTime() + within.toNanos();
        while (received.size() < stopAt && System.nanoTime() < deadline) {
            consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                if (key.equals(record.key())) {
                    received.add(record);
                }
            });
        }
    }

    private static String typeOf(ConsumerRecord<String, String> record) {
        return new String(record.headers().lastHeader(EventHeaders.TYPE).value(), StandardCharsets.UTF_8);
    }

    private UUID join(String name) {
        UUID user = UUID.randomUUID();
        joinSession.join(session.roomCode().value(), user, name);
        return user;
    }

    private UUID playerIdOf(UUID user) {
        return players.find(session.id(), user).orElseThrow().playerId();
    }
}
