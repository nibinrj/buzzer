package dev.nibin.buzzer.quiz.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One question of a quiz. Immutable: editing a question means replacing it (same id) through the Quiz.
 * <p>
 * Two kinds of rules:
 * <ul>
 *   <li>Always enforced, so an invalid Question can't exist: text, time limit 5-60 s, at most 6 options.</li>
 *   <li>Checked only when the quiz is published ({@link #publishProblems()}): at least 2 options and
 *       exactly one correct. This lets a host save a draft question before its options are finished.</li>
 * </ul>
 */
public final class Question {

    public static final int MAX_TEXT_LENGTH = 300;
    public static final int MIN_TIME_LIMIT_SECONDS = 5;
    public static final int MAX_TIME_LIMIT_SECONDS = 60;
    public static final int MIN_OPTIONS_TO_PUBLISH = 2;
    public static final int MAX_OPTIONS = 6;

    private final UUID id;
    private final String text;
    private final int timeLimitSeconds;
    private final List<Option> options;

    /** Rehydrates a stored question, or builds a replacement that keeps an existing id. */
    public Question(UUID id, String text, int timeLimitSeconds, List<Option> options) {
        this.id = Objects.requireNonNull(id, "id");
        this.text = Text.require(text, "question text", MAX_TEXT_LENGTH);
        if (timeLimitSeconds < MIN_TIME_LIMIT_SECONDS || timeLimitSeconds > MAX_TIME_LIMIT_SECONDS) {
            throw new InvalidQuizException("timeLimitSeconds must be between " + MIN_TIME_LIMIT_SECONDS
                    + " and " + MAX_TIME_LIMIT_SECONDS);
        }
        this.timeLimitSeconds = timeLimitSeconds;
        Objects.requireNonNull(options, "options");
        if (options.size() > MAX_OPTIONS) {
            throw new InvalidQuizException("a question can have at most " + MAX_OPTIONS + " options");
        }
        // List.copyOf rejects null elements and makes the list unmodifiable; order is kept.
        this.options = List.copyOf(options);
    }

    /** A brand-new question with a fresh id. */
    public static Question create(String text, int timeLimitSeconds, List<Option> options) {
        return new Question(UUID.randomUUID(), text, timeLimitSeconds, options);
    }

    /** What stops this question from being published, in words a host understands; empty if nothing. */
    public List<String> publishProblems() {
        List<String> problems = new ArrayList<>();
        if (options.size() < MIN_OPTIONS_TO_PUBLISH) {
            problems.add("needs at least " + MIN_OPTIONS_TO_PUBLISH + " options (has " + options.size() + ")");
        }
        long correct = options.stream().filter(Option::correct).count();
        if (correct != 1) {
            problems.add("needs exactly one correct option (has " + correct + ")");
        }
        return problems;
    }

    public UUID id() {
        return id;
    }

    public String text() {
        return text;
    }

    public int timeLimitSeconds() {
        return timeLimitSeconds;
    }

    public List<Option> options() {
        return options;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Question question && id.equals(question.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Question[id=" + id + ", options=" + options.size() + "]";
    }
}
