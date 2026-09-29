package dev.nibin.buzzer.scoring.domain;

import java.util.UUID;

/**
 * scoring_db, as the application sees it. Every method is safe to call concurrently and in any order: each is one
 * atomic statement. Callers run them inside one transaction per event.
 */
public interface ScoringRepository {

    /**
     * Records that an event is being applied.
     *
     * @return true the first time for this eventId, false if it was already applied (a redelivery)
     */
    boolean markProcessed(UUID eventId);

    /** Adds one accepted answer to the player's total, creating the total on the player's first answer. */
    void addAnswer(UUID sessionId, UUID playerId, int points, boolean correct);

    /** Stores what SessionStarted says. Keeps an end that arrived first. */
    void sessionStarted(UUID sessionId, int questionCount, long startedAtMs);

    /** Marks the session ended. Keeps a start that arrived first, and creates the row if it didn't. */
    void sessionEnded(UUID sessionId, long endedAtMs);
}
