package dev.nibin.buzzer.benchmarks;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SubmitAnswerBenchmark compares two ways of deciding an answer. That only means something if they decide the same:
 * this plays one sequence of answers through each, against a real Redis, and expects the same replies from both.
 */
class SubmitAnswerEquivalenceTest {

    private static final long TTL_SECONDS = 600;
    private static final long FUTURE = System.currentTimeMillis() + 3_600_000;

    private static GenericContainer<?> container;
    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;
    private static RedisCommands<String, String> redis;
    private static SubmitAnswer submit;

    @BeforeAll
    static void start() {
        container = new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);
        container.start();
        client = RedisClient.create(RedisURI.create(container.getHost(), container.getMappedPort(6379)));
        connection = client.connect();
        redis = connection.sync();
        submit = new SubmitAnswer(redis);
    }

    @AfterAll
    static void stop() {
        connection.close();
        client.shutdown();
        container.stop();
    }

    @FunctionalInterface
    interface Variant {
        SubmitAnswer.Result submit(SubmitAnswer.Keys keys, String playerId, int questionIndex, boolean correct);
    }

    @Test
    void scriptAndSeparateCommandsDecideEveryCaseTheSame() {
        List<String> expected = List.of(
                "ACCEPTED 1 1", // first correct answer
                "ACCEPTED 2 0", // a wrong answer still takes a place in line
                "ACCEPTED 3 2", // second correct answer
                "DUPLICATE 1 0", // the first player again: their original seq
                "WRONG_QUESTION 0 0",
                "CLOSED 0 0",
                "LATE 0 0",
                "NOT_RUNNING 0 0");

        List<String> viaScript = play((keys, player, index, correct) ->
                submit.viaScript(keys, player, index, correct, TTL_SECONDS));
        List<String> viaCommands = play((keys, player, index, correct) ->
                submit.viaSeparateCommands(keys, player, index, correct, TTL_SECONDS));

        assertThat(viaScript).isEqualTo(expected);
        assertThat(viaCommands).isEqualTo(expected);
    }

    @Test
    void bothSetTheTtlOnEveryKeyTheyWrite() {
        for (Variant variant : List.<Variant>of(
                (keys, player, index, correct) -> submit.viaScript(keys, player, index, correct, TTL_SECONDS),
                (keys, player, index, correct) -> submit.viaSeparateCommands(keys, player, index, correct, TTL_SECONDS))) {
            SubmitAnswer.Keys keys = freshQuestion();
            submit.setState(keys, "IN_PROGRESS", 0, true, FUTURE);

            SubmitAnswer.Result result = variant.submit(keys, "p1", 0, true);

            assertThat(result.answeredAtMs()).isPositive();
            assertThat(redis.ttl(keys.answered())).isBetween(1L, TTL_SECONDS);
            assertThat(redis.ttl(keys.seq())).isBetween(1L, TTL_SECONDS);
            assertThat(redis.ttl(keys.correct())).isBetween(1L, TTL_SECONDS);
        }
    }

    /** The same answers, in the same order, on a question of its own. answeredAtMs is left out: it's a clock. */
    private static List<String> play(Variant variant) {
        SubmitAnswer.Keys keys = freshQuestion();
        List<SubmitAnswer.Result> results = new ArrayList<>();

        submit.setState(keys, "IN_PROGRESS", 0, true, FUTURE);
        results.add(variant.submit(keys, "p1", 0, true));
        results.add(variant.submit(keys, "p2", 0, false));
        results.add(variant.submit(keys, "p3", 0, true));
        results.add(variant.submit(keys, "p1", 0, true));
        results.add(variant.submit(keys, "p4", 1, true));

        submit.setState(keys, "IN_PROGRESS", 0, false, FUTURE);
        results.add(variant.submit(keys, "p4", 0, true));

        submit.setState(keys, "IN_PROGRESS", 0, true, System.currentTimeMillis() - 60_000);
        results.add(variant.submit(keys, "p4", 0, true));

        submit.setState(keys, "ENDED", 0, true, FUTURE);
        results.add(variant.submit(keys, "p4", 0, true));

        return results.stream().map(r -> r.outcome() + " " + r.seq() + " " + r.correctRank()).toList();
    }

    private static SubmitAnswer.Keys freshQuestion() {
        return SubmitAnswer.Keys.of(UUID.randomUUID(), UUID.randomUUID());
    }
}
