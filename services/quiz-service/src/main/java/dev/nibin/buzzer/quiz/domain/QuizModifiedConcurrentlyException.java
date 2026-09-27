package dev.nibin.buzzer.quiz.domain;

import java.util.UUID;

/** Thrown when saving a quiz that someone else saved after it was loaded (its version is stale). */
public class QuizModifiedConcurrentlyException extends RuntimeException {

    public QuizModifiedConcurrentlyException(UUID quizId) {
        super("Quiz " + quizId + " was changed by someone else; reload it and try again");
    }
}
