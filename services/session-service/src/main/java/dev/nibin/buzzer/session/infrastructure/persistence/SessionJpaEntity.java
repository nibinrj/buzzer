package dev.nibin.buzzer.session.infrastructure.persistence;

import dev.nibin.buzzer.session.domain.Session;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** JPA mapping of sessions (+ session_questions). Created only through JpaSessionRepositoryAdapter. */
@Entity
@Table(name = "sessions")
public class SessionJpaEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "room_code", nullable = false, length = 6)
    private String roomCode;

    @Column(name = "quiz_id", nullable = false)
    private UUID quizId;

    @Column(name = "host_id", nullable = false)
    private UUID hostId;

    @Column(name = "quiz_title", nullable = false, length = 120)
    private String quizTitle;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Session.Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // The frozen quiz. @OrderColumn writes each question's list index into "position".
    // @Immutable: Hibernate never updates or deletes these rows, and refuses if the list is changed.
    @ElementCollection
    @CollectionTable(name = "session_questions", joinColumns = @JoinColumn(name = "session_id"))
    @OrderColumn(name = "position")
    @Immutable
    private List<SessionQuestionEmbeddable> questions = new ArrayList<>();

    @Transient
    private boolean newEntity = true;

    /** Required by JPA. */
    protected SessionJpaEntity() {
    }

    SessionJpaEntity(UUID id, String roomCode, UUID quizId, UUID hostId, String quizTitle, Session.Status status,
            Instant createdAt, List<SessionQuestionEmbeddable> questions) {
        this.id = id;
        this.roomCode = roomCode;
        this.quizId = quizId;
        this.hostId = hostId;
        this.quizTitle = quizTitle;
        this.status = status;
        this.createdAt = createdAt;
        this.questions.addAll(questions);
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        this.newEntity = false;
    }

    /** New until persisted or loaded: save() then INSERTs straight away instead of SELECTing by id first. */
    @Override
    public boolean isNew() {
        return newEntity;
    }

    /** Public because Persistable requires it. */
    @Override
    public UUID getId() {
        return id;
    }

    String getRoomCode() {
        return roomCode;
    }

    UUID getQuizId() {
        return quizId;
    }

    UUID getHostId() {
        return hostId;
    }

    String getQuizTitle() {
        return quizTitle;
    }

    Session.Status getStatus() {
        return status;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    List<SessionQuestionEmbeddable> getQuestions() {
        return questions;
    }
}
