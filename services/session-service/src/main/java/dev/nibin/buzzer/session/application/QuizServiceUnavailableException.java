package dev.nibin.buzzer.session.application;

import java.util.UUID;

/**
 * quiz-service couldn't be asked: connection refused, timed out, answered 5xx, or the circuit breaker is open
 * and didn't even try. A session can't start without its questions, so the API answers 503.
 */
public class QuizServiceUnavailableException extends RuntimeException {

    public QuizServiceUnavailableException(UUID quizId, Throwable cause) {
        super("quiz-service unavailable while fetching quiz " + quizId, cause);
    }
}
