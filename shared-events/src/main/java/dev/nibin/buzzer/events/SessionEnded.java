package dev.nibin.buzzer.events;

import java.util.UUID;

/**
 * A session ended: no more answers will come. Published on {@value SessionLifecycle#TOPIC}, keyed by sessionId, so
 * it arrives after that session's SessionStarted, with the Kafka header {@value EventHeaders#TYPE} =
 * "SessionEnded". Scoring persists final results on it.
 *
 * @param endedAtMs     epoch millis, by the clock the question deadlines are set by
 * @param schemaVersion {@value #SCHEMA_VERSION} for this shape
 */
public record SessionEnded(UUID eventId, UUID sessionId, long endedAtMs, int schemaVersion) {

    public static final int SCHEMA_VERSION = 1;
}
