package dev.nibin.buzzer.session.application;

import java.util.UUID;

/**
 * No published quiz with this id belongs to the caller: it doesn't exist, is still a draft, or is someone
 * else's. Deliberately one answer (404), so hosts can't probe which quiz ids exist, same as quiz-service.
 */
public class QuizNotFoundException extends RuntimeException {

    public QuizNotFoundException(UUID quizId) {
        super("Quiz " + quizId + " not found");
    }
}
