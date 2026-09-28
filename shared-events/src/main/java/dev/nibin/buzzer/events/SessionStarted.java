package dev.nibin.buzzer.events;

import java.util.UUID;

/**
 * A session left the lobby and its first question is running. Published on {@value SessionLifecycle#TOPIC}, keyed
 * by sessionId, with the Kafka header {@value EventHeaders#TYPE} = "SessionStarted".
 *
 * @param questionCount how many questions the session will play
 * @param startedAtMs   epoch millis, by the clock the question deadlines are set by
 * @param schemaVersion {@value #SCHEMA_VERSION} for this shape
 */
public record SessionStarted(UUID eventId, UUID sessionId, UUID quizId, UUID hostId, int questionCount,
        long startedAtMs, int schemaVersion) {

    public static final int SCHEMA_VERSION = 1;
}
