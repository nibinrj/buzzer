package dev.nibin.buzzer.quiz.domain;

import java.util.List;

/** Thrown by publish() with every rule violation at once, so the host can fix them all in one go. */
public class QuizNotPublishableException extends RuntimeException {

    private final List<String> problems;

    public QuizNotPublishableException(List<String> problems) {
        super("Quiz cannot be published: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
