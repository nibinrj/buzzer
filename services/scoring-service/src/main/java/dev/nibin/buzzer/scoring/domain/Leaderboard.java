package dev.nibin.buzzer.scoring.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A fast copy of each session's standings (Redis). Postgres holds the truth; this copy can vanish at any time
 * (expiry, a Redis restart) and is then rebuilt from Postgres. Every write only ever raises a player's points, so
 * writes can arrive late, twice, or in any order.
 */
public interface Leaderboard {

    /** How many players the leaderboard shows and ScoreUpdated carries. */
    int SIZE = 10;

    /**
     * Sets the player's points to {@code total} unless the copy already has more.
     *
     * @return empty if this session's copy doesn't exist: {@link #rebuild} it, then call again
     */
    Optional<Placement> raise(UUID sessionId, PlayerPoints total);

    /** Writes every player's points, e.g. after {@link #raise} or {@link #top} found the copy missing. */
    void rebuild(UUID sessionId, List<PlayerPoints> totals);

    /** The best {@link #SIZE}, best first; empty if this session's copy doesn't exist. */
    Optional<List<PlayerPoints>> top(UUID sessionId);

    /**
     * @param position the raised player's place, 0 = first
     * @param top      the best {@link #SIZE} right after the write, best first
     */
    record Placement(long position, List<PlayerPoints> top) {

        public boolean inTop() {
            return position < SIZE;
        }
    }
}
