package dev.nibin.buzzer.quiz.domain;

/**
 * One answer a player can pick. A value object: no id, compared by value, replaced together with its question.
 * Always valid: text is stripped, not blank, at most 120 characters.
 */
public record Option(String text, boolean correct) {

    public static final int MAX_TEXT_LENGTH = 120;

    public Option {
        text = Text.require(text, "option text", MAX_TEXT_LENGTH);
    }
}
