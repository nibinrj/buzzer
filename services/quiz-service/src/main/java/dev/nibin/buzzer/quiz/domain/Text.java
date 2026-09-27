package dev.nibin.buzzer.quiz.domain;

import java.util.Objects;

/** The domain's one rule for user-entered text: stripped, not blank, and within a length limit. */
final class Text {

    private Text() {
    }

    /**
     * @return the value with leading and trailing whitespace removed
     * @throws InvalidQuizException if blank or longer than maxLength characters
     */
    static String require(String value, String name, int maxLength) {
        Objects.requireNonNull(value, name);
        String stripped = value.strip();
        if (stripped.isEmpty()) {
            throw new InvalidQuizException(name + " must not be blank");
        }
        // Count code points, not UTF-16 chars: an emoji is 2 chars in Java but 1 character in
        // Postgres VARCHAR(n), and the database limit is what this mirrors.
        if (stripped.codePointCount(0, stripped.length()) > maxLength) {
            throw new InvalidQuizException(name + " must be at most " + maxLength + " characters");
        }
        return stripped;
    }
}
