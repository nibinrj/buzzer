package dev.nibin.buzzer.session.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One question as frozen into a session. An option is identified by its index in {@code options};
 * {@code correctOption} is the index of the one correct option.
 * <p>
 * Only the rules the game depends on are checked here. Text lengths and time limits are quiz-service's rules,
 * already enforced when the quiz was published (and again by session_db's constraints).
 */
public record SessionQuestion(UUID questionId, String text, int timeLimitSeconds, List<String> options,
        int correctOption) {

    public static final int MIN_OPTIONS = 2;

    public SessionQuestion {
        Objects.requireNonNull(questionId, "questionId");
        Objects.requireNonNull(text, "text");
        // List.copyOf rejects null elements and makes the list unmodifiable; order is kept.
        options = List.copyOf(options);
        if (options.size() < MIN_OPTIONS) {
            throw new IllegalArgumentException("a question needs at least " + MIN_OPTIONS + " options");
        }
        if (correctOption < 0 || correctOption >= options.size()) {
            throw new IllegalArgumentException("correctOption " + correctOption + " is not an option index");
        }
    }
}
