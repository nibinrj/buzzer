package dev.nibin.buzzer.session.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

/**
 * JPA mapping of answers. Plain id columns, no relationships: an answer is written once and never navigated from.
 * Same Persistable pattern as PlayerJpaEntity.
 */
@Entity
@Table(name = "answers")
public class AnswerJpaEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "question_id", nullable = false)
    private UUID questionId;

    @Column(name = "player_id", nullable = false)
    private UUID playerId;

    @Column(name = "option_index", nullable = false)
    private int optionIndex;

    @Column(name = "correct", nullable = false)
    private boolean correct;

    @Column(name = "seq", nullable = false)
    private long seq;

    @Column(name = "correct_rank", nullable = false)
    private int correctRank;

    @Column(name = "answered_at", nullable = false)
    private Instant answeredAt;

    @Transient
    private boolean newEntity = true;

    /** Required by JPA. */
    protected AnswerJpaEntity() {
    }

    AnswerJpaEntity(UUID id, UUID sessionId, UUID questionId, UUID playerId, int optionIndex, boolean correct,
            long seq, int correctRank, Instant answeredAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.questionId = questionId;
        this.playerId = playerId;
        this.optionIndex = optionIndex;
        this.correct = correct;
        this.seq = seq;
        this.correctRank = correctRank;
        this.answeredAt = answeredAt;
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

    UUID getSessionId() {
        return sessionId;
    }

    UUID getQuestionId() {
        return questionId;
    }

    UUID getPlayerId() {
        return playerId;
    }

    int getOptionIndex() {
        return optionIndex;
    }

    boolean isCorrect() {
        return correct;
    }

    long getSeq() {
        return seq;
    }

    int getCorrectRank() {
        return correctRank;
    }

    Instant getAnsweredAt() {
        return answeredAt;
    }
}
