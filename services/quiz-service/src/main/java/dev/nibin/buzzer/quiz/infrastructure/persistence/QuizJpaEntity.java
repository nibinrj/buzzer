package dev.nibin.buzzer.quiz.infrastructure.persistence;

import dev.nibin.buzzer.quiz.domain.Quiz;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.BatchSize;
import org.springframework.data.domain.Persistable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** JPA mapping of quizzes. Created and changed only through QuizMapper and the adapter. */
@Entity
@Table(name = "quizzes")
public class QuizJpaEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(nullable = false, length = 120)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Quiz.Status status;

    // Deliberately NOT @Version: JPA's @Version only increments when this row changes, but a quiz also
    // changes when only a question row changes. The adapter increments it explicitly on every save.
    @Column(nullable = false)
    private long version;

    // Inverse side (QuestionJpaEntity.quiz owns the link). cascade: saving the quiz saves its questions;
    // orphanRemoval: a question dropped from this list is deleted.
    @OneToMany(mappedBy = "quiz", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position")
    @BatchSize(size = 50)
    private List<QuestionJpaEntity> questions = new ArrayList<>();

    @Transient
    private boolean newEntity = true;

    /** Required by JPA. */
    protected QuizJpaEntity() {
    }

    QuizJpaEntity(UUID id, UUID ownerId, String title, Quiz.Status status, long version) {
        this.id = id;
        this.ownerId = ownerId;
        this.title = title;
        this.status = status;
        this.version = version;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }

    @Override
    public boolean isNew() {
        return newEntity;
    }

    /** Public because Persistable requires it. */
    @Override
    public UUID getId() {
        return id;
    }

    void update(String title, Quiz.Status status) {
        this.title = title;
        this.status = status;
    }

    /** Keeps the same managed list instance (Hibernate tracks it); anything left out becomes an orphan. */
    void replaceQuestions(List<QuestionJpaEntity> newQuestions) {
        questions.clear();
        questions.addAll(newQuestions);
    }

    UUID getOwnerId() {
        return ownerId;
    }

    String getTitle() {
        return title;
    }

    Quiz.Status getStatus() {
        return status;
    }

    long getVersion() {
        return version;
    }

    List<QuestionJpaEntity> getQuestions() {
        return questions;
    }
}
