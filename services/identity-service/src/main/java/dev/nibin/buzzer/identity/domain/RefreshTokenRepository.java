package dev.nibin.buzzer.identity.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** Port for storing refresh tokens. The state changes are atomic: they are safe under concurrent requests. */
public interface RefreshTokenRepository {

    void add(RefreshToken token);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    /**
     * Marks an ACTIVE token as used, atomically.
     *
     * @return true only for the one caller that changed it; false if it was already used, revoked or
     *         expired, including when another request used it a moment earlier
     */
    boolean markUsed(UUID tokenId, Instant now);

    /**
     * Revokes every not-yet-revoked token in the family.
     *
     * @return how many tokens this call revoked
     */
    int revokeFamily(UUID familyId, Instant now);
}
