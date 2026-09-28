package dev.nibin.buzzer.session.infrastructure.redis;

import dev.nibin.buzzer.session.ApiIntegrationTest;
import dev.nibin.buzzer.session.domain.AnswerRegistration;
import dev.nibin.buzzer.session.domain.AnswerRegistration.Outcome;
import dev.nibin.buzzer.session.domain.AnswerRegistry;
import dev.nibin.buzzer.session.domain.LiveStateRepository;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The buzz race under real contention: many answers released at the same instant against a real Redis 7, through
 * FOUR independent Redis connections, as four session-service tasks would send them. (One Spring context shares a
 * single Lettuce connection between all threads, which would line the commands up before they even reach Redis;
 * separate connections make them genuinely arrive together.)
 * <p>
 * It targets the AnswerRegistry, the script, because that is where the race is decided. Going through the whole
 * SubmitAnswer use case would add two Postgres reads per answer, and the pool of 10 connections would then be what
 * lines the answers up, not Redis. The use case's own rules are covered by SubmitAnswerTest and the STOMP tests.
 * <p>
 * Phase 4 is done when this class passes 20 runs in a row (see the project plan, batch 4.4).
 */
@ApiIntegrationTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SubmitAnswerConcurrencyTest {

    private static final Logger log = LoggerFactory.getLogger(SubmitAnswerConcurrencyTest.class);
    private static final int EXTRA_INSTANCES = 3;
    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private AnswerRegistry appRegistry;

    @Autowired
    private LiveStateRepository liveState;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    @Qualifier("redisContainer")
    private GenericContainer<?> redisContainer;

    private final List<LettuceConnectionFactory> extraConnections = new ArrayList<>();
    private final List<AnswerRegistry> instances = new ArrayList<>();

    /** One session for the class (PER_CLASS); each test races on a question of its own, so on keys of its own. */
    private final UUID sessionId = UUID.randomUUID();

    /**
     * The app's own registry plus three more, each on its own Redis connection. Then one throwaway race: the extra
     * connections only connect on first use, and the first virtual threads are slow to start. Measured cold, the
     * first race's release spread was 88 ms; warm it is 0-3 ms.
     */
    @BeforeAll
    void startInstances() throws Exception {
        instances.add(appRegistry);
        for (int i = 0; i < EXTRA_INSTANCES; i++) {
            LettuceConnectionFactory connection = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                    redisContainer.getHost(), redisContainer.getMappedPort(6379)));
            connection.afterPropertiesSet();
            connection.start();
            extraConnections.add(connection);
            instances.add(new RedisAnswerRegistry(new StringRedisTemplate(connection), Duration.ofHours(6)));
        }
        UUID noSuchSession = UUID.randomUUID(); // answered NOT_RUNNING, writes nothing
        race(40, i -> instances.get(i % instances.size())
                .register(noSuchSession, UUID.randomUUID(), 0, UUID.randomUUID(), false));
    }

    @AfterAll
    void stopInstances() {
        extraConnections.forEach(LettuceConnectionFactory::destroy);
    }

    @Test
    void twoHundredPlayersAtOnceGetSeqOneToTwoHundredAndOneFirstCorrect() throws Exception {
        UUID question = UUID.randomUUID();
        openQuestion(question, liveState.serverTime().plusSeconds(30));
        List<UUID> players = IntStream.range(0, 200).mapToObj(i -> UUID.randomUUID()).toList();

        Race race = race(200, i -> register(i, question, players.get(i), isCorrect(i)));
        assertReleasedTogether(race);

        List<Buzz> buzzes = IntStream.range(0, 200)
                .mapToObj(i -> new Buzz(players.get(i), isCorrect(i), race.results().get(i)))
                .sorted(Comparator.comparingLong(buzz -> buzz.result().seq()))
                .toList();
        assertThat(buzzes).allSatisfy(buzz -> assertThat(buzz.result().outcome()).isEqualTo(Outcome.ACCEPTED));
        // No gaps, no duplicates: exactly 1..200.
        assertThat(buzzes).extracting(buzz -> buzz.result().seq())
                .containsExactlyElementsOf(LongStream.rangeClosed(1, 200).boxed().toList());
        // In arrival order, the correct answers are ranked 1, 2, 3... with no gaps; the wrong ones have no rank.
        List<Integer> ranksInArrivalOrder = buzzes.stream().filter(Buzz::correct)
                .map(buzz -> buzz.result().correctRank()).toList();
        assertThat(ranksInArrivalOrder)
                .containsExactlyElementsOf(IntStream.rangeClosed(1, correctCount(200)).boxed().toList());
        assertThat(buzzes).filteredOn(buzz -> !buzz.correct())
                .allSatisfy(buzz -> assertThat(buzz.result().correctRank()).isZero());
        assertThat(buzzes).filteredOn(buzz -> buzz.result().correctRank() == 1).hasSize(1);
        // What the players were told is exactly what Redis recorded.
        assertThat(redis.opsForZSet().range(SessionKeys.answered(sessionId, question), 0, -1))
                .containsExactlyElementsOf(buzzes.stream().map(buzz -> buzz.player().toString()).toList());
    }

    @Test
    void onePlayerSendingFiftyTimesAtOnceIsAcceptedExactlyOnce() throws Exception {
        UUID question = UUID.randomUUID();
        openQuestion(question, liveState.serverTime().plusSeconds(30));
        UUID ada = UUID.randomUUID();

        Race race = race(50, i -> register(i, question, ada, true));
        assertReleasedTogether(race);

        assertThat(race.results()).filteredOn(AnswerRegistration::accepted).hasSize(1)
                .allSatisfy(accepted -> assertThat(accepted.seq()).isEqualTo(1));
        assertThat(race.results()).filteredOn(result -> !result.accepted()).hasSize(49)
                .allSatisfy(duplicate -> {
                    assertThat(duplicate.outcome()).isEqualTo(Outcome.DUPLICATE);
                    assertThat(duplicate.seq()).isEqualTo(1); // all point at the one that counted
                });
        assertThat(redis.opsForZSet().size(SessionKeys.answered(sessionId, question))).isEqualTo(1);
        assertThat(redis.opsForValue().get(SessionKeys.answerSeq(sessionId, question))).isEqualTo("1");
    }

    @Test
    void aWaveAfterTheDeadlineIsAllLateAndTakesNoNumbers() throws Exception {
        UUID question = UUID.randomUUID();
        Instant deadline = liveState.serverTime().plusSeconds(2);
        openQuestion(question, deadline);

        Race inTime = race(100, i -> register(i, question, UUID.randomUUID(), isCorrect(i)));
        awaitRedisTimePast(deadline);
        Race late = race(100, i -> register(i, question, UUID.randomUUID(), isCorrect(i)));
        assertReleasedTogether(inTime);
        assertReleasedTogether(late);

        assertThat(inTime.results()).allSatisfy(result -> {
            assertThat(result.outcome()).isEqualTo(Outcome.ACCEPTED);
            assertThat(result.answeredAt()).isBeforeOrEqualTo(deadline);
        });
        assertThat(inTime.results()).extracting(AnswerRegistration::seq)
                .containsExactlyInAnyOrderElementsOf(LongStream.rangeClosed(1, 100).boxed().toList());
        assertThat(late.results()).allSatisfy(result ->
                assertThat(result).isEqualTo(new AnswerRegistration(Outcome.LATE, 0, 0, null)));
        // The late wave left no trace: still 100 answers, and the counter still at 100.
        assertThat(redis.opsForZSet().size(SessionKeys.answered(sessionId, question))).isEqualTo(100);
        assertThat(redis.opsForValue().get(SessionKeys.answerSeq(sessionId, question))).isEqualTo("100");
    }

    // --- the race ---

    /**
     * Runs {@code count} submissions on virtual threads. Every thread first reports ready and waits at a closed
     * gate; the gate opens once all are waiting, so they start as close together as the JVM allows.
     */
    private Race race(int count, IntFunction<AnswerRegistration> submission) throws Exception {
        CountDownLatch ready = new CountDownLatch(count);
        CountDownLatch gate = new CountDownLatch(1);
        long[] startedAt = new long[count];
        List<Future<AnswerRegistration>> futures = new ArrayList<>(count);
        try (ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                int index = i;
                futures.add(threads.submit(() -> {
                    ready.countDown();
                    gate.await();
                    startedAt[index] = System.nanoTime();
                    return submission.apply(index);
                }));
            }
            assertThat(ready.await(WAIT.toSeconds(), TimeUnit.SECONDS)).as("all threads at the gate").isTrue();
            gate.countDown();
            List<AnswerRegistration> results = new ArrayList<>(count);
            for (Future<AnswerRegistration> future : futures) {
                results.add(future.get(WAIT.toSeconds(), TimeUnit.SECONDS));
            }
            // Reading startedAt here is safe: each Future.get() above happens-after its thread's write.
            long spread = LongStream.of(startedAt).max().orElseThrow() - LongStream.of(startedAt).min().orElseThrow();
            log.info("{} submissions released within {} ms", count, TimeUnit.NANOSECONDS.toMillis(spread));
            return new Race(results, TimeUnit.NANOSECONDS.toMillis(spread));
        }
    }

    /**
     * The batch asks for a release within about 10 ms; warm, it is 0-3 ms here (logged on every race). The bound is
     * looser so a GC pause can't fail the 20-runs check; it still catches a race that turned sequential.
     */
    private static void assertReleasedTogether(Race race) {
        assertThat(race.releaseSpreadMillis()).as("ms from the first thread through the gate to the last")
                .isLessThan(50);
    }

    /** Submission {@code i} goes through instance {@code i mod 4}: the answers arrive on four connections. */
    private AnswerRegistration register(int i, UUID question, UUID player, boolean correct) {
        return instances.get(i % instances.size()).register(sessionId, question, 0, player, correct);
    }

    /** Every third answer is correct: a mix, so correctRank has gaps in seq to skip over. */
    private static boolean isCorrect(int i) {
        return i % 3 == 0;
    }

    private static int correctCount(int total) {
        return (int) IntStream.range(0, total).filter(SubmitAnswerConcurrencyTest::isCorrect).count();
    }

    private void openQuestion(UUID question, Instant deadline) {
        // The script only checks the running index (0 here); each test's own question id gives it fresh keys.
        liveState.showQuestion(sessionId, 0, deadline);
    }

    /** Waits by Redis's clock, the one the script judges with; this JVM's clock may be off from it. */
    private void awaitRedisTimePast(Instant deadline) throws InterruptedException {
        while (!liveState.serverTime().isAfter(deadline)) {
            Thread.sleep(50);
        }
    }

    /** @param results in submission order; releaseSpreadMillis: first to last thread through the gate */
    private record Race(List<AnswerRegistration> results, long releaseSpreadMillis) {
    }

    private record Buzz(UUID player, boolean correct, AnswerRegistration result) {
    }
}
