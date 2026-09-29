package dev.nibin.buzzer.scoring.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * scoring_db, as the application sees it. Every write is one atomic statement, safe to run concurrently and in any
 * order. Callers run the writes for one event inside one transaction.
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

    /** Raises the session's leaderboard version by one, creating the session's row if needed. */
    void bumpVersion(UUID sessionId);

    /** Stores what SessionStarted says. Keeps an end that arrived first. */
    void sessionStarted(UUID sessionId, int questionCount, long startedAtMs);

    /** Marks the session ended. Keeps a start that arrived first, and creates the row if it didn't. */
    void sessionEnded(UUID sessionId, long endedAtMs);

    Optional<ScoringSession> session(UUID sessionId);

    Optional<PlayerPoints> pointsOf(UUID sessionId, UUID playerId);

    /** Every player with a total in this session, best first; equal points by playerId, descending (as Redis). */
    List<PlayerTotal> totals(UUID sessionId);
}
