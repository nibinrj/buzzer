package dev.nibin.buzzer.quiz.application;

import java.util.UUID;

/**
 * The quiz doesn't exist, or it isn't yours: deliberately the same, so other users can't even learn
 * that a quiz id exists (404, not 403).
 */
public class QuizNotFoundException extends RuntimeException {

    public QuizNotFoundException(UUID quizId) {
        super("Quiz " + quizId + " not found");
    }
}
