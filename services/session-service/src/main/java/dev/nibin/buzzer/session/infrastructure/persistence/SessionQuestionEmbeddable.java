package dev.nibin.buzzer.session.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.List;
import java.util.UUID;

/**
 * One row of session_questions. An embeddable, not an entity: a question has no life outside its session,
 * and its key (session_id, position) is exactly what an @ElementCollection with @OrderColumn gives.
 */
@Embeddable
public class SessionQuestionEmbeddable {

    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    @Column(nullable = false, length = 300)
    private String text;

    @Column(name = "time_limit_seconds", nullable = false)
    private int timeLimitSeconds;

    // A PostgreSQL varchar[] column: Hibernate maps a List<String> to an SQL array when told to.
    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(nullable = false)
    private List<String> options;

    @Column(name = "correct_option", nullable = false)
    private int correctOption;

    /** Required by JPA. */
    protected SessionQuestionEmbeddable() {
    }

    SessionQuestionEmbeddable(UUID questionId, String text, int timeLimitSeconds, List<String> options,
            int correctOption) {
        this.questionId = questionId;
        this.text = text;
        this.timeLimitSeconds = timeLimitSeconds;
        this.options = options;
        this.correctOption = correctOption;
    }

    UUID getQuestionId() {
        return questionId;
    }

    String getText() {
        return text;
    }

    int getTimeLimitSeconds() {
        return timeLimitSeconds;
    }

    List<String> getOptions() {
        return options;
    }

    int getCorrectOption() {
        return correctOption;
    }
}
