package dev.nibin.buzzer.session.infrastructure.redis;

import dev.nibin.buzzer.session.ApiIntegrationTest;
import dev.nibin.buzzer.session.domain.AnswerRegistration;
import dev.nibin.buzzer.session.domain.AnswerRegistration.Outcome;
import dev.nibin.buzzer.session.domain.AnswerRegistry;
import dev.nibin.buzzer.session.domain.LiveStateRepository;
import dev.nibin.buzzer.session.domain.Session;
import io.lettuce.core.cluster.SlotHash;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * submit_answer.lua against a real Redis 7, one outcome at a time. Every test uses a new session id. The question
 * is opened the way the game opens it (LiveStateRepository.showQuestion), with a deadline by Redis's clock.
 * Many answers at once is batch 4.4's SubmitAnswerConcurrencyTest.
 */
@ApiIntegrationTest
class RedisAnswerRegistryTest {

    private static final Duration SIX_HOURS = Duration.ofHours(6);

    @Autowired
    private AnswerRegistry answers;

    @Autowired
    private LiveStateRepository liveState;

    @Autowired
    private StringRedisTemplate redis;

    private final UUID sessionId = UUID.randomUUID();
    private final UUID questionId = UUID.randomUUID();

    @Test
    void theFirstCorrectAnswerIsFirstInLineAndFirstCorrect() {
        openQuestion(0, inThirtySeconds());

        assertThat(answer(0, UUID.randomUUID(), true)).isEqualTo(accepted(1, 1));
    }

    @Test
    void aWrongAnswerCountsInLineButGetsNoRank() {
        openQuestion(0, inThirtySeconds());

        assertThat(answer(0, UUID.randomUUID(), false)).isEqualTo(accepted(1, 0));
    }

    @Test
    void seqCountsEveryAcceptedAnswerAndCorrectRankOnlyTheCorrectOnes() {
        openQuestion(0, inThirtySeconds());

        List<Verdict> results = List.of(
                answer(0, UUID.randomUUID(), false),
                answer(0, UUID.randomUUID(), true),
                answer(0, UUID.randomUUID(), false),
                answer(0, UUID.randomUUID(), true),
                answer(0, UUID.randomUUID(), true));

        assertThat(results).containsExactly(
                accepted(1, 0), accepted(2, 1), accepted(3, 0), accepted(4, 2), accepted(5, 3));
    }

    @Test
    void aSecondAnswerIsADuplicateWithTheFirstSeqAndTakesNoNumber() {
        openQuestion(0, inThirtySeconds());
        UUID ada = UUID.randomUUID();
        answer(0, ada, false);
        answer(0, UUID.randomUUID(), true);

        Verdict again = answer(0, ada, true); // now with the right option: too bad, the first one stands

        assertThat(again).isEqualTo(new Verdict(Outcome.DUPLICATE, 1, 0));
        assertThat(answer(0, UUID.randomUUID(), true)).isEqualTo(accepted(3, 2)); // no gap: 1, 2, 3
        assertThat(redis.opsForZSet().score(SessionKeys.answered(sessionId, questionId), ada.toString()))
                .isEqualTo(1.0);
    }

    @Test
    void afterTheDeadlineIsLateAndLeavesNoTrace() {
        openQuestion(0, liveState.serverTime().minusSeconds(1));

        assertThat(answer(0, UUID.randomUUID(), true)).isEqualTo(rejected(Outcome.LATE));
        assertThat(redis.hasKey(SessionKeys.answered(sessionId, questionId))).isFalse();
        assertThat(redis.hasKey(SessionKeys.answerSeq(sessionId, questionId))).isFalse();
        assertThat(redis.hasKey(SessionKeys.correctCount(sessionId, questionId))).isFalse();
    }

    @Test
    void aRetryAfterTheDeadlineStillHearsItsFirstAnswerCounted() {
        openQuestion(0, inThirtySeconds());
        UUID ada = UUID.randomUUID();
        answer(0, ada, true);
        openQuestion(0, liveState.serverTime().minusSeconds(1)); // the deadline passes

        assertThat(answer(0, ada, true)).isEqualTo(new Verdict(Outcome.DUPLICATE, 1, 0));
        assertThat(answer(0, UUID.randomUUID(), true)).isEqualTo(rejected(Outcome.LATE));
    }

    @Test
    void aClosedQuestionTakesNoAnswersEvenBeforeItsDeadline() {
        openQuestion(0, inThirtySeconds());
        liveState.closeQuestion(sessionId); // what reveal does

        assertThat(answer(0, UUID.randomUUID(), true)).isEqualTo(rejected(Outcome.CLOSED));
    }

    @Test
    void aQuestionWithoutTheOpenFlagCountsAsClosed() {
        // State as written before batch 4.2: pointer and deadline, no questionOpen.
        redis.opsForHash().putAll(SessionKeys.state(sessionId), Map.of("status", "IN_PROGRESS",
                "questionIndex", "0", "questionDeadline", String.valueOf(inThirtySeconds().toEpochMilli())));

        assertThat(answer(0, UUID.randomUUID(), true)).isEqualTo(rejected(Outcome.CLOSED));
    }

    @Test
    void anAnswerToAnotherQuestionThanTheRunningOneIsRefused() {
        openQuestion(1, inThirtySeconds());

        assertThat(answer(0, UUID.randomUUID(), true)).isEqualTo(rejected(Outcome.WRONG_QUESTION));
    }

    @Test
    void noAnswersInTheLobbyAfterTheEndOrWithoutAnyState() {
        UUID lobby = UUID.randomUUID();
        liveState.initialize(lobby, Session.Status.LOBBY);
        UUID ended = UUID.randomUUID();
        liveState.showQuestion(ended, 0, inThirtySeconds());
        liveState.end(ended);

        assertThat(verdict(answers.register(lobby, questionId, 0, UUID.randomUUID(), true)))
                .isEqualTo(rejected(Outcome.NOT_RUNNING));
        assertThat(verdict(answers.register(ended, questionId, 0, UUID.randomUUID(), true)))
                .isEqualTo(rejected(Outcome.NOT_RUNNING));
        assertThat(verdict(answers.register(UUID.randomUUID(), questionId, 0, UUID.randomUUID(), true)))
                .isEqualTo(rejected(Outcome.NOT_RUNNING));
    }

    @Test
    void anAcceptedAnswerIsStampedWithRedisTimeAndARejectedOneIsNot() {
        openQuestion(0, inThirtySeconds());
        Instant before = liveState.serverTime();
        UUID ada = UUID.randomUUID();

        AnswerRegistration accepted = answers.register(sessionId, questionId, 0, ada, true);
        AnswerRegistration duplicate = answers.register(sessionId, questionId, 0, ada, true);

        // Redis's clock, so compared with Redis's own TIME read around it, never with this JVM's clock (the two
        // differ by over a second on Docker Desktop; see RedisLiveStateRepositoryTest.serverTimeIsRedisClock).
        assertThat(accepted.answeredAt()).isBetween(before, liveState.serverTime());
        assertThat(duplicate.answeredAt()).isNull();
    }

    @Test
    void theAnswerKeysExpireWithTheLiveState() {
        openQuestion(0, inThirtySeconds());

        answer(0, UUID.randomUUID(), true);

        assertExpiresInAboutSixHours(SessionKeys.answered(sessionId, questionId));
        assertExpiresInAboutSixHours(SessionKeys.answerSeq(sessionId, questionId));
        assertExpiresInAboutSixHours(SessionKeys.correctCount(sessionId, questionId));
    }

    @Test
    void aFlushedScriptCacheIsRefilledOnTheNextCall() {
        openQuestion(0, inThirtySeconds());
        String sha1 = RedisScript.of(new ClassPathResource("lua/submit_answer.lua")).getSha1();
        // As after a Redis restart or failover: EVALSHA now gets NOSCRIPT.
        redis.execute((RedisCallback<Void>) connection -> {
            connection.scriptingCommands().scriptFlush();
            return null;
        });

        assertThat(answer(0, UUID.randomUUID(), true)).isEqualTo(accepted(1, 1)); // fell back to EVAL
        assertThat(redis.execute((RedisCallback<List<Boolean>>) connection ->
                connection.scriptingCommands().scriptExists(sha1))).containsExactly(true);
    }

    @Test
    void allKeysOfASessionShareOneClusterSlot() {
        // Only the {...} part is hashed, so every key's slot is the session id's slot.
        int slot = SlotHash.getSlot(sessionId.toString());

        assertThat(List.of(
                SessionKeys.state(sessionId),
                SessionKeys.players(sessionId),
                SessionKeys.answered(sessionId, questionId),
                SessionKeys.answerSeq(sessionId, questionId),
                SessionKeys.correctCount(sessionId, questionId)))
                .allSatisfy(key -> assertThat(SlotHash.getSlot(key)).as(key).isEqualTo(slot));
    }

    // --- helpers ---

    private void openQuestion(int index, Instant deadline) {
        liveState.showQuestion(sessionId, index, deadline);
    }

    private Instant inThirtySeconds() {
        return liveState.serverTime().plusSeconds(30);
    }

    private Verdict answer(int questionIndex, UUID playerId, boolean correct) {
        return verdict(answers.register(sessionId, questionId, questionIndex, playerId, correct));
    }

    /** The registration without its time, which a test can't know in advance (answeredAt has its own test). */
    private static Verdict verdict(AnswerRegistration registration) {
        return new Verdict(registration.outcome(), registration.seq(), registration.correctRank());
    }

    private static Verdict accepted(long seq, int correctRank) {
        return new Verdict(Outcome.ACCEPTED, seq, correctRank);
    }

    private static Verdict rejected(Outcome outcome) {
        return new Verdict(outcome, 0, 0);
    }

    private record Verdict(Outcome outcome, long seq, int correctRank) {
    }

    private void assertExpiresInAboutSixHours(String key) {
        Long seconds = redis.getExpire(key);
        assertThat(seconds).as(key).isBetween(SIX_HOURS.minusMinutes(1).toSeconds(), SIX_HOURS.toSeconds());
    }
}
