package dev.nibin.buzzer.events;

import java.util.UUID;

/**
 * An answer was accepted and recorded by session-service. Published on {@value #TOPIC}, keyed by sessionId, so all
 * of one session's answers stay in order on one partition. Consumers must be idempotent on {@code eventId}: delivery
 * is at-least-once.
 *
 * @param eventId       unique per answer (session-service uses the answer's own id), the idempotency key
 * @param playerId      the player's membership id in the session, not their user id
 * @param optionId      the chosen option, by index into the question's options
 * @param correct       whether that option is the question's correct one
 * @param correctRank   place among correct answers to this question, from 1; 0 when not correct
 * @param seq           place in arrival order among accepted answers to this question, from 1
 * @param answeredAtMs  when it was accepted, epoch millis, by the clock the question's deadline was set by
 * @param schemaVersion {@value #SCHEMA_VERSION} for this shape
 */
public record AnswerSubmitted(UUID eventId, UUID sessionId, UUID questionId, UUID playerId, int optionId,
        boolean correct, int correctRank, long seq, long answeredAtMs, int schemaVersion) {

    public static final String TOPIC = "session.answer-submitted";
    public static final int SCHEMA_VERSION = 1;
}
