package dev.nibin.buzzer.events;

import java.util.List;
import java.util.UUID;

/**
 * A session's leaderboard changed. Published by scoring-service on {@value #TOPIC}, keyed by sessionId, with the Kafka
 * header {@value EventHeaders#TYPE} = "ScoreUpdated". A whole snapshot, not a delta: a newer one replaces an older
 * one, so consumers keep the highest {@code version} they have seen and ignore anything older. Delivery is
 * at-least-once, and the same version can arrive more than once.
 *
 * @param eventId       unique per publication
 * @param top10         the best players, best first, at most 10; ties share a rank (1, 2, 2, 4)
 * @param version       per session, only ever grows: bumped with every scored answer
 * @param schemaVersion {@value #SCHEMA_VERSION} for this shape
 */
public record ScoreUpdated(UUID eventId, UUID sessionId, List<Entry> top10, long version, int schemaVersion) {

    public static final String TOPIC = "scoring.score-updated";
    public static final int SCHEMA_VERSION = 1;

    /**
     * @param playerId the player's membership id in the session; clients get the display name from session-service
     */
    public record Entry(int rank, UUID playerId, int points) {
    }
}
