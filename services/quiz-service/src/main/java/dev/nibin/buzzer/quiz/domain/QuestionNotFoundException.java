package dev.nibin.buzzer.quiz.domain;

import java.util.UUID;

/** Thrown when replacing or removing a question id that isn't part of the quiz. */
public class QuestionNotFoundException extends RuntimeException {

    public QuestionNotFoundException(UUID questionId) {
        super("Question " + questionId + " is not part of this quiz");
    }
}
