package dev.nibin.buzzer.session.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * An accepted answer, as recorded. Only accepted answers are recorded: its seq and correctRank come from the
 * AnswerRegistry's verdict, and answeredAt from the live state's clock.
 *
 * @param playerId    the player's membership id (Player.playerId), not their user id
 * @param optionIndex the chosen option, by index into the question's options
 * @param correctRank place among correct answers from 1; 0 exactly when the answer is wrong
 */
public record Answer(UUID answerId, UUID sessionId, UUID questionId, UUID playerId, int optionIndex,
        boolean correct, long seq, int correctRank, Instant answeredAt) {

    public Answer {
        Objects.requireNonNull(answerId, "answerId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(questionId, "questionId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(answeredAt, "answeredAt");
        if (optionIndex < 0 || seq < 1 || correctRank < 0) {
            throw new IllegalArgumentException("optionIndex and correctRank must be >= 0, seq >= 1");
        }
        if (correct != (correctRank > 0)) {
            throw new IllegalArgumentException("a correct answer has a correctRank, a wrong one has none");
        }
    }

    /** Records an answer the registry accepted. */
    public static Answer accepted(UUID sessionId, UUID questionId, UUID playerId, int optionIndex, boolean correct,
            AnswerRegistration registration) {
        if (!registration.accepted()) {
            throw new IllegalArgumentException("only an accepted answer is recorded, not " + registration.outcome());
        }
        return new Answer(UUID.randomUUID(), sessionId, questionId, playerId, optionIndex, correct,
                registration.seq(), registration.correctRank(), registration.answeredAt());
    }
}
