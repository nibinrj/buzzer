package dev.nibin.buzzer.scoring.infrastructure.kafka;

import dev.nibin.buzzer.events.AnswerSubmitted;
import dev.nibin.buzzer.events.EventHeaders;
import dev.nibin.buzzer.events.ScoreUpdated;
import dev.nibin.buzzer.scoring.ScoringIntegrationTest;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Answers in, ScoreUpdated out, through real Redpanda, Postgres and Redis: AnswerSubmitted records are produced the
 * way session-service's outbox does, and scoring.score-updated is read by a plain consumer.
 */
@ScoringIntegrationTest
class LeaderboardFlowTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, String> kafka;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private JsonMapper json;

    @Autowired
    private StringRedisTemplate redis;

    private final UUID session = UUID.randomUUID();

    @Test
    void eachUpdateCarriesTheRankedTopWithARisingVersionAndPlayersOutsideTheTopPublishNothing() throws Exception {
        List<UUID> players = IntStream.range(0, 11).mapToObj(i -> UUID.randomUUID()).toList();
        for (int i = 0; i < 10; i++) {
            send(answer(players.get(i), true, i + 1)); // correctRank 1..10: 1000, 900, ... 400, 300, 300, 300
        }
        send(answer(players.get(10), false, 0)); // 0 points: 11th, outside the top 10 → no update (version 11)
        send(answer(players.get(0), false, 0)); // the leader again, 0 points: still in the top → update, version 12

        List<ScoreUpdated> updates = readUntil(update -> update.version() == 12);

        assertThat(updates).extracting(ScoreUpdated::version)
                .containsExactlyElementsOf(LongStream.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12).boxed().toList());
        ScoreUpdated last = updates.getLast();
        assertThat(last.top10()).extracting(ScoreUpdated.Entry::points)
                .containsExactly(1000, 900, 800, 700, 600, 500, 400, 300, 300, 300);
        assertThat(last.top10()).extracting(ScoreUpdated.Entry::rank).containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 8, 8);
        assertThat(last.top10()).extracting(ScoreUpdated.Entry::playerId).doesNotContain(players.get(10));
        assertThat(last.top10().getFirst().playerId()).isEqualTo(players.get(0));
    }

    @Test
    void aLostRedisLeaderboardIsRebuiltFromPostgresOnTheNextAnswer() throws Exception {
        UUID ada = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        send(answer(ada, true, 1));
        readUntil(update -> update.version() == 1);

        redis.delete("leaderboard:{" + session + "}"); // as after a Redis restart without persistence
        send(answer(bob, true, 2));

        ScoreUpdated second = readUntil(update -> update.version() == 2).getLast();
        assertThat(second.top10()).containsExactly(
                new ScoreUpdated.Entry(1, ada, 1000), new ScoreUpdated.Entry(2, bob, 900)); // ada came from Postgres
    }

    private AnswerSubmitted answer(UUID player, boolean correct, int correctRank) {
        return new AnswerSubmitted(UUID.randomUUID(), session, UUID.randomUUID(), player, 0, correct, correctRank,
                1, 1_000L, AnswerSubmitted.SCHEMA_VERSION);
    }

    private void send(AnswerSubmitted answer) throws Exception {
        ProducerRecord<String, String> record = new ProducerRecord<>(AnswerSubmitted.TOPIC, session.toString(),
                json.writeValueAsString(answer));
        record.headers().add(EventHeaders.TYPE, "AnswerSubmitted".getBytes(StandardCharsets.UTF_8));
        kafka.send(record).get(10, TimeUnit.SECONDS);
    }

    /**
     * This session's ScoreUpdated records, from the beginning of the topic, until one matches. Each must be keyed by
     * the session and typed by its header.
     */
    private List<ScoreUpdated> readUntil(Predicate<ScoreUpdated> last) {
        Map<String, Object> config = new HashMap<>(kafkaAdmin.getConfigurationProperties());
        config.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        List<ScoreUpdated> updates = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer =
                     new KafkaConsumer<>(config, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(ScoreUpdated.TOPIC));
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (!session.toString().equals(record.key())) {
                        continue;
                    }
                    assertThat(new String(record.headers().lastHeader(EventHeaders.TYPE).value(),
                            StandardCharsets.UTF_8)).isEqualTo("ScoreUpdated");
                    ScoreUpdated update = json.readValue(record.value(), ScoreUpdated.class);
                    updates.add(update);
                    if (last.test(update)) {
                        return updates;
                    }
                }
            }
        }
        throw new AssertionError("no matching ScoreUpdated for " + session + " within " + WAIT + "; got " + updates);
    }
}
