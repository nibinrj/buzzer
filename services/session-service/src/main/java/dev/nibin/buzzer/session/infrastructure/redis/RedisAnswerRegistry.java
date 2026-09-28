package dev.nibin.buzzer.session.infrastructure.redis;

import dev.nibin.buzzer.session.application.LiveStateUnavailableException;
import dev.nibin.buzzer.session.domain.AnswerRegistration;
import dev.nibin.buzzer.session.domain.AnswerRegistration.Outcome;
import dev.nibin.buzzer.session.domain.AnswerRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Runs lua/submit_answer.lua (read it first: the rules live there, this class only passes arguments and reads the
 * reply).
 * <p>
 * The script object is built once, and it computes the SHA1 of the script text once. Every call then sends
 * EVALSHA (the 40-char hash, not the script); if Redis doesn't know that hash (restart, failover, SCRIPT FLUSH), it
 * answers NOSCRIPT and Spring Data Redis resends it once as EVAL, which caches it again.
 */
@Component
class RedisAnswerRegistry implements AnswerRegistry {

    @SuppressWarnings("rawtypes") // the reply mixes a String and Longs; List.class is the only fitting result type
    private static final RedisScript<List> SUBMIT_ANSWER =
            RedisScript.of(new ClassPathResource("lua/submit_answer.lua"), List.class);

    private final StringRedisTemplate redis;
    private final String ttlSeconds;

    RedisAnswerRegistry(StringRedisTemplate redis, @Value("${session.live-state.ttl}") Duration ttl) {
        this.redis = redis;
        this.ttlSeconds = String.valueOf(ttl.toSeconds());
    }

    @Override
    public AnswerRegistration register(UUID sessionId, UUID questionId, int questionIndex, UUID playerId,
            boolean correct) {
        List<String> keys = List.of(
                SessionKeys.state(sessionId),
                SessionKeys.answered(sessionId, questionId),
                SessionKeys.answerSeq(sessionId, questionId),
                SessionKeys.correctCount(sessionId, questionId));
        List<?> reply;
        try {
            reply = redis.execute(SUBMIT_ANSWER, keys,
                    playerId.toString(), String.valueOf(questionIndex), correct ? "1" : "0", ttlSeconds);
        } catch (DataAccessException e) {
            // Safe to retry: if the script did run and only the reply was lost, the retry answers DUPLICATE with
            // the seq the first attempt got.
            throw new LiveStateUnavailableException(e);
        }
        return toRegistration(reply);
    }

    /**
     * The script returns {outcome, seq, correctRank, answeredAtMs}: a bulk string, which the template's String
     * serializer turns into a String, and three integers, which arrive as Longs. answeredAtMs is 0 unless accepted.
     */
    private static AnswerRegistration toRegistration(List<?> reply) {
        if (reply == null || reply.size() != 4) {
            throw new IllegalStateException("submit_answer.lua returned " + reply);
        }
        Outcome outcome = Outcome.valueOf((String) reply.get(0));
        return new AnswerRegistration(
                outcome,
                ((Number) reply.get(1)).longValue(),
                ((Number) reply.get(2)).intValue(),
                outcome == Outcome.ACCEPTED ? Instant.ofEpochMilli(((Number) reply.get(3)).longValue()) : null);
    }
}
