package dev.nibin.buzzer.benchmarks;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * One accepted answer: submit_answer.lua via EVALSHA vs the same nine commands sent one by one (HMGET, ZSCORE, TIME,
 * INCR, ZADD, INCR, 3 × EXPIRE). Both take the longest path, a correct answer from a player who hasn't answered yet.
 * <p>
 * Each fork starts its own Redis 7 container (Docker must be running) and talks to it over Docker's port mapping, so
 * a round trip here costs more than inside one network. Compare the two numbers with each other, not with production.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
@State(Scope.Benchmark)
public class SubmitAnswerBenchmark {

    private static final long TTL_SECONDS = Duration.ofHours(6).toSeconds(); // session.live-state.ttl

    private GenericContainer<?> container;
    private RedisClient client;
    private StatefulRedisConnection<String, String> connection;
    private SubmitAnswer submit;
    private SubmitAnswer.Keys keys;
    private long nextPlayer;

    @Setup(Level.Trial)
    public void start() {
        container = new GenericContainer<>(DockerImageName.parse("redis:7")).withExposedPorts(6379);
        container.start();
        client = RedisClient.create(RedisURI.create(container.getHost(), container.getMappedPort(6379)));
        connection = client.connect();
        submit = new SubmitAnswer(connection.sync());
        keys = SubmitAnswer.Keys.of(UUID.randomUUID(), UUID.randomUUID());
        submit.setState(keys, "IN_PROGRESS", 0, true, System.currentTimeMillis() + Duration.ofHours(1).toMillis());
    }

    /** A fresh question every iteration, so the sorted set doesn't keep growing through the whole run. */
    @Setup(Level.Iteration)
    public void newQuestion() {
        submit.clearAnswers(keys);
    }

    @TearDown(Level.Trial)
    public void stop() {
        connection.close();
        client.shutdown();
        container.stop();
    }

    @Benchmark
    public SubmitAnswer.Result script() {
        return submit.viaScript(keys, "player-" + nextPlayer++, 0, true, TTL_SECONDS);
    }

    @Benchmark
    public SubmitAnswer.Result separateCommands() {
        return submit.viaSeparateCommands(keys, "player-" + nextPlayer++, 0, true, TTL_SECONDS);
    }
}
