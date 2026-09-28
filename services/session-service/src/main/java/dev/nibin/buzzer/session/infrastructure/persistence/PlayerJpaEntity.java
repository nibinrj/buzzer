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
 * JPA mapping of players. Its own entity, not a collection on SessionJpaEntity: a session can have 500 players,
 * and adding one must not load the other 499. session_id is a plain column for the same reason.
 */
@Entity
@Table(name = "players")
public class PlayerJpaEntity implements Persistable<UUID> {

    @Id
    private UUID id;

    @Column(name = "session_id", nullable = false)
    private UUID sessionId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "display_name", nullable = false, length = 30)
    private String displayName;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt;

    @Transient
    private boolean newEntity = true;

    /** Required by JPA. */
    protected PlayerJpaEntity() {
    }

    PlayerJpaEntity(UUID id, UUID sessionId, UUID userId, String displayName, Instant joinedAt) {
        this.id = id;
        this.sessionId = sessionId;
        this.userId = userId;
        this.displayName = displayName;
        this.joinedAt = joinedAt;
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

    UUID getUserId() {
        return userId;
    }

    String getDisplayName() {
        return displayName;
    }

    Instant getJoinedAt() {
        return joinedAt;
    }
}
