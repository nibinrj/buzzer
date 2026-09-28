package dev.nibin.buzzer.session.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Someone who joined a session. playerId is this membership's own id, shown to other players;
 * userId is the JWT sub and stays server-side.
 */
public record Player(UUID playerId, UUID sessionId, UUID userId, String displayName, Instant joinedAt) {

    public static final int MAX_DISPLAY_NAME_LENGTH = 30;

    public Player {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(joinedAt, "joinedAt");
        Objects.requireNonNull(displayName, "displayName");
        displayName = displayName.strip();
        if (displayName.isEmpty() || displayName.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException("displayName must be 1-" + MAX_DISPLAY_NAME_LENGTH + " characters");
        }
    }

    public static Player join(UUID sessionId, UUID userId, String displayName, Instant now) {
        return new Player(UUID.randomUUID(), sessionId, userId, displayName, now);
    }
}
