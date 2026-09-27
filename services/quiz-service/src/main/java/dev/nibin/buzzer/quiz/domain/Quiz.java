package dev.nibin.buzzer.quiz.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Aggregate root: a quiz and its ordered questions. All changes go through this class, which is how the
 * rules are kept: every change throws once the quiz is PUBLISHED, and publish() checks completeness.
 * Pure domain object: no Spring, JPA or Jackson.
 */
public final class Quiz {

    public enum Status {
        DRAFT, PUBLISHED
    }

    public static final int MAX_TITLE_LENGTH = 120;
    public static final int MAX_QUESTIONS = 50;

    private final UUID id;
    private final UUID ownerId;
    private String title;
    private Status status;
    private final List<Question> questions;
    // Optimistic-locking version, owned by persistence: the domain only carries it from load to save.
    private final long version;

    /** Rehydrates a stored quiz. Only the always-enforced rules are checked here, not the publish rules. */
    public Quiz(UUID id, UUID ownerId, String title, Status status, List<Question> questions, long version) {
        this.id = Objects.requireNonNull(id, "id");
        this.ownerId = Objects.requireNonNull(ownerId, "ownerId");
        this.title = Text.require(title, "title", MAX_TITLE_LENGTH);
        this.status = Objects.requireNonNull(status, "status");
        Objects.requireNonNull(questions, "questions");
        if (questions.size() > MAX_QUESTIONS) {
            throw new InvalidQuizException("a quiz can have at most " + MAX_QUESTIONS + " questions");
        }
        Set<UUID> ids = new HashSet<>();
        for (Question question : questions) {
            if (!ids.add(Objects.requireNonNull(question, "question").id())) {
                throw new InvalidQuizException("duplicate question id " + question.id());
            }
        }
        this.questions = new ArrayList<>(questions);
        this.version = version;
    }

    /** A new, empty draft owned by the given user (the JWT sub). */
    public static Quiz create(UUID ownerId, String title) {
        return new Quiz(UUID.randomUUID(), ownerId, title, Status.DRAFT, List.of(), 0);
    }

    public void rename(String newTitle) {
        requireDraft();
        this.title = Text.require(newTitle, "title", MAX_TITLE_LENGTH);
    }

    /** Appends a question and returns it (with its new id). */
    public Question addQuestion(String text, int timeLimitSeconds, List<Option> options) {
        requireDraft();
        if (questions.size() >= MAX_QUESTIONS) {
            throw new InvalidQuizException("a quiz can have at most " + MAX_QUESTIONS + " questions");
        }
        Question question = Question.create(text, timeLimitSeconds, options);
        questions.add(question);
        return question;
    }

    /** Replaces a question in place: same id, same position, new content. */
    public Question replaceQuestion(UUID questionId, String text, int timeLimitSeconds, List<Option> options) {
        requireDraft();
        int index = indexOf(questionId);
        Question replacement = new Question(questionId, text, timeLimitSeconds, options);
        questions.set(index, replacement);
        return replacement;
    }

    public void removeQuestion(UUID questionId) {
        requireDraft();
        questions.remove(indexOf(questionId));
    }

    /**
     * DRAFT → PUBLISHED, if the quiz is complete.
     *
     * @throws QuizNotPublishableException listing every problem, e.g. "Question 2: needs exactly one correct option"
     * @throws QuizNotEditableException if it is already published
     */
    public void publish() {
        requireDraft();
        List<String> problems = new ArrayList<>();
        if (questions.isEmpty()) {
            problems.add("A quiz needs at least one question");
        }
        for (int i = 0; i < questions.size(); i++) {
            for (String problem : questions.get(i).publishProblems()) {
                problems.add("Question " + (i + 1) + ": " + problem); // 1-based, as the host sees them
            }
        }
        if (!problems.isEmpty()) {
            throw new QuizNotPublishableException(problems);
        }
        this.status = Status.PUBLISHED;
    }

    private void requireDraft() {
        if (status == Status.PUBLISHED) {
            throw new QuizNotEditableException(id);
        }
    }

    private int indexOf(UUID questionId) {
        for (int i = 0; i < questions.size(); i++) {
            if (questions.get(i).id().equals(questionId)) {
                return i;
            }
        }
        throw new QuestionNotFoundException(questionId);
    }

    public UUID id() {
        return id;
    }

    public UUID ownerId() {
        return ownerId;
    }

    public String title() {
        return title;
    }

    public Status status() {
        return status;
    }

    /** A read-only snapshot: callers can't change the quiz through it. */
    public List<Question> questions() {
        return List.copyOf(questions);
    }

    public long version() {
        return version;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Quiz quiz && id.equals(quiz.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Quiz[id=" + id + ", ownerId=" + ownerId + ", status=" + status + ", questions=" + questions.size() + "]";
    }
}
