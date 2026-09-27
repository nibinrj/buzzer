package dev.nibin.buzzer.quiz.domain;

import java.util.UUID;

/** Thrown by every change to a PUBLISHED quiz: once published, a quiz is immutable. */
public class QuizNotEditableException extends RuntimeException {

    public QuizNotEditableException(UUID quizId) {
        super("Quiz " + quizId + " is published and can no longer be changed");
    }
}
