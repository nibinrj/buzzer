package dev.nibin.buzzer.session.domain;

import java.time.Instant;
import java.util.Objects;

/**
 * The verdict on one submitted answer, decided in one atomic step for all session-service instances.
 *
 * @param seq         the answer's place in arrival order among accepted answers to this question, from 1 with no
 *                    gaps. For {@link Outcome#DUPLICATE}: the seq the player's first answer got. Otherwise 0.
 * @param correctRank the answer's place among accepted CORRECT answers, from 1. 0 if not accepted or not correct.
 * @param answeredAt  when it was accepted, by the live state's clock (the clock the deadline is set by). Present
 *                    exactly when the outcome is {@link Outcome#ACCEPTED}, null otherwise.
 */
public record AnswerRegistration(Outcome outcome, long seq, int correctRank, Instant answeredAt) {

    public enum Outcome {
        /** Counted. */
        ACCEPTED,
        /** This player already answered this question; the first answer stands. */
        DUPLICATE,
        /** After the question's deadline. */
        LATE,
        /** The question was revealed (closed) already. */
        CLOSED,
        /** Not the question that is running now (an earlier one, or none). */
        WRONG_QUESTION,
        /** The session is in the lobby or has ended. */
        NOT_RUNNING
    }

    public AnswerRegistration {
        Objects.requireNonNull(outcome, "outcome");
        if ((outcome == Outcome.ACCEPTED) != (answeredAt != null)) {
            throw new IllegalArgumentException("answeredAt is required for ACCEPTED and only for ACCEPTED");
        }
    }

    public boolean accepted() {
        return outcome == Outcome.ACCEPTED;
    }
}
