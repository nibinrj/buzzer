package dev.nibin.buzzer.identity.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One issued refresh token (only its hash). Pure domain object: no Spring, JPA or Jackson.
 * Immutable: marking a token used or revoked happens in the database with atomic conditional
 * updates (see RefreshTokenRepository), because two requests can race for the same token.
 */
public final class RefreshToken {

    public enum Status {
        ACTIVE, USED, REVOKED, EXPIRED
    }

    private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-f]{64}");

    private final UUID id;
    private final UUID userId;
    private final UUID familyId;
    private final String tokenHash;
    private final Instant createdAt;
    private final Instant expiresAt;
    private final Instant usedAt;     // null until rotated
    private final Instant revokedAt;  // null unless its family was revoked

    /** Rehydrates a stored token. usedAt and revokedAt may be null. */
    public RefreshToken(UUID id, UUID userId, UUID familyId, String tokenHash, Instant createdAt, Instant expiresAt,
            Instant usedAt, Instant revokedAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.familyId = Objects.requireNonNull(familyId, "familyId");
        this.tokenHash = Objects.requireNonNull(tokenHash, "tokenHash");
        if (!SHA_256_HEX.matcher(tokenHash).matches()) {
            throw new IllegalArgumentException("tokenHash must be 64 lowercase hex characters");
        }
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(createdAt)) {
            throw new IllegalArgumentException("expiresAt must be after createdAt");
        }
        this.usedAt = usedAt;
        this.revokedAt = revokedAt;
    }

    /**
     * A new token in a family. Pass a new familyId at login, the old token's familyId on rotation.
     */
    public static RefreshToken issue(UUID userId, UUID familyId, String tokenHash, Instant now, Duration ttl) {
        return new RefreshToken(UUID.randomUUID(), userId, familyId, tokenHash, now, now.plus(ttl), null, null);
    }

    /**
     * Precedence matters: a revoked family is final; a used token presented again is a reuse signal
     * even after it expired; only an unused, unrevoked token can merely be expired.
     */
    public Status status(Instant now) {
        if (revokedAt != null) {
            return Status.REVOKED;
        }
        if (usedAt != null) {
            return Status.USED;
        }
        if (!now.isBefore(expiresAt)) {
            return Status.EXPIRED;
        }
        return Status.ACTIVE;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public UUID familyId() {
        return familyId;
    }

    public String tokenHash() {
        return tokenHash;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    public Optional<Instant> usedAt() {
        return Optional.ofNullable(usedAt);
    }

    public Optional<Instant> revokedAt() {
        return Optional.ofNullable(revokedAt);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RefreshToken token && id.equals(token.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        // No tokenHash: keep anything token-derived out of logs.
        return "RefreshToken[id=" + id + ", userId=" + userId + ", familyId=" + familyId
                + ", expiresAt=" + expiresAt + ", usedAt=" + usedAt + ", revokedAt=" + revokedAt + "]";
    }
}
