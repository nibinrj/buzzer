package dev.nibin.buzzer.quiz.infrastructure.persistence;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;
import org.hibernate.annotations.BatchSize;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** JPA mapping of questions (+ question_options). Created and changed only through QuizMapper. */
@Entity
@Table(name = "questions")
public class QuestionJpaEntity {

    @Id
    private UUID id;

    // Owning side of quiz 1-n questions: this column is what links a question to its quiz.
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "quiz_id", nullable = false)
    private QuizJpaEntity quiz;

    // Set explicitly by the mapper (the index in the domain's list); QuizJpaEntity sorts by it.
    @Column(nullable = false)
    private int position;

    @Column(nullable = false, length = 300)
    private String text;

    @Column(name = "time_limit_seconds", nullable = false)
    private int timeLimitSeconds;

    // Values without identity, stored and replaced with the question. @OrderColumn keeps their order.
    // @BatchSize: loading options for one question loads them for up to 50 questions in one query.
    @ElementCollection
    @CollectionTable(name = "question_options", joinColumns = @JoinColumn(name = "question_id"))
    @OrderColumn(name = "position")
    @BatchSize(size = 50)
    private List<OptionEmbeddable> options = new ArrayList<>();

    /** Required by JPA. */
    protected QuestionJpaEntity() {
    }

    QuestionJpaEntity(UUID id, QuizJpaEntity quiz, int position, String text, int timeLimitSeconds,
            List<OptionEmbeddable> options) {
        this.id = id;
        this.quiz = quiz;
        update(position, text, timeLimitSeconds, options);
    }

    /** Hibernate writes only what actually changed: same values, no UPDATE; same options, no SQL. */
    void update(int position, String text, int timeLimitSeconds, List<OptionEmbeddable> options) {
        this.position = position;
        this.text = text;
        this.timeLimitSeconds = timeLimitSeconds;
        this.options.clear();
        this.options.addAll(options);
    }

    UUID getId() {
        return id;
    }

    int getPosition() {
        return position;
    }

    String getText() {
        return text;
    }

    int getTimeLimitSeconds() {
        return timeLimitSeconds;
    }

    List<OptionEmbeddable> getOptions() {
        return options;
    }
}
