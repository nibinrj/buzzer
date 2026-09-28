package dev.nibin.buzzer.session.domain;

import java.util.UUID;

/**
 * Port: the single authority on who answered what, in which order (Redis, batch 4.2). One call checks and records
 * an answer as ONE atomic step, so concurrent answers through different instances still get one agreed order.
 * <p>
 * It judges only what the live state knows: which question runs, whether it is open, the deadline, and who
 * answered already. Whether the chosen option is correct is decided by the caller, from the session's stored
 * questions; the client never says so.
 */
public interface AnswerRegistry {

    /**
     * @param questionIndex the index of {@code questionId} in the session's questions
     * @param correct       whether the chosen option is the question's correct one
     */
    AnswerRegistration register(UUID sessionId, UUID questionId, int questionIndex, UUID playerId, boolean correct);
}
