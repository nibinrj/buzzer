package dev.nibin.buzzer.benchmarks;

import io.lettuce.core.KeyValue;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * session-service's answer decision two ways, against one Redis:
 * <ul>
 * <li>{@link #viaScript}: lua/submit_answer.lua (the file from session-service's jar) with EVALSHA, one round trip;</li>
 * <li>{@link #viaSeparateCommands}: the script's reads and writes as one Java call each, in the same order.</li>
 * </ul>
 * Only for measuring them side by side. The second one is not safe to use: between its ZSCORE and its ZADD another
 * session-service instance can accept the same player's answer, and two answers can take the same place in line.
 * That race is why the script exists (ADR-004).
 */
final class SubmitAnswer {

    /** The script's reply, in its order. seq and correctRank are 0 unless accepted (or seq, for a duplicate). */
    record Result(String outcome, long seq, long correctRank, long answeredAtMs) {
    }

    /** One question's keys, named exactly as session-service's SessionKeys names them. */
    record Keys(String state, String answered, String seq, String correct) {

        static Keys of(UUID sessionId, UUID questionId) {
            String answered = "buzz:{" + sessionId + "}:" + questionId;
            return new Keys("session:{" + sessionId + "}:state", answered, answered + ":seq", answered + ":correct");
        }

        String[] all() {
            return new String[] {state, answered, seq, correct};
        }
    }

    private final RedisCommands<String, String> redis;
    private final String sha;

    SubmitAnswer(RedisCommands<String, String> redis) {
        this.redis = redis;
        // SCRIPT LOAD once; every call after that sends only the SHA-1, as Spring Data Redis does in the service.
        this.sha = redis.scriptLoad(script());
    }

    Result viaScript(Keys keys, String playerId, int questionIndex, boolean correct, long ttlSeconds) {
        List<Object> reply = redis.evalsha(sha, ScriptOutputType.MULTI, keys.all(),
                playerId, String.valueOf(questionIndex), correct ? "1" : "0", String.valueOf(ttlSeconds));
        return new Result((String) reply.get(0), (Long) reply.get(1), (Long) reply.get(2), (Long) reply.get(3));
    }

    /** Line for line the script's checks and writes; each redis.* call is one network round trip. */
    Result viaSeparateCommands(Keys keys, String playerId, int questionIndex, boolean correct, long ttlSeconds) {
        List<KeyValue<String, String>> state = redis.hmget(keys.state(),
                "status", "questionIndex", "questionDeadline", "questionOpen");
        String status = state.get(0).getValueOrElse(null);
        String index = state.get(1).getValueOrElse(null);
        String deadline = state.get(2).getValueOrElse(null);
        String open = state.get(3).getValueOrElse(null);

        if (!"IN_PROGRESS".equals(status)) {
            return rejected("NOT_RUNNING");
        }
        if (!String.valueOf(questionIndex).equals(index)) {
            return rejected("WRONG_QUESTION");
        }
        if (!"1".equals(open) || deadline == null) {
            return rejected("CLOSED");
        }

        Double previous = redis.zscore(keys.answered(), playerId);
        if (previous != null) {
            return new Result("DUPLICATE", previous.longValue(), 0, 0);
        }

        List<String> time = redis.time();
        long nowMs = Long.parseLong(time.get(0)) * 1000 + Long.parseLong(time.get(1)) / 1000;
        if (nowMs > Long.parseLong(deadline)) {
            return rejected("LATE");
        }

        long seq = redis.incr(keys.seq());
        redis.zadd(keys.answered(), seq, playerId);
        long correctRank = correct ? redis.incr(keys.correct()) : 0;
        redis.expire(keys.answered(), ttlSeconds);
        redis.expire(keys.seq(), ttlSeconds);
        redis.expire(keys.correct(), ttlSeconds);
        return new Result("ACCEPTED", seq, correctRank, nowMs);
    }

    /** The fields of session:{S}:state that the script reads, in the form session-service writes them. */
    void setState(Keys keys, String status, int questionIndex, boolean open, long deadlineMs) {
        redis.hset(keys.state(), Map.of(
                "status", status,
                "questionIndex", String.valueOf(questionIndex),
                "questionOpen", open ? "1" : "0",
                "questionDeadline", String.valueOf(deadlineMs)));
    }

    /** Forgets every answer to the question: the next call starts again at seq 1. */
    void clearAnswers(Keys keys) {
        redis.del(keys.answered(), keys.seq(), keys.correct());
    }

    private static Result rejected(String outcome) {
        return new Result(outcome, 0, 0, 0);
    }

    private static String script() {
        try (InputStream in = SubmitAnswer.class.getClassLoader().getResourceAsStream("lua/submit_answer.lua")) {
            if (in == null) {
                throw new IllegalStateException("lua/submit_answer.lua not on the classpath (session-service's jar)");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
