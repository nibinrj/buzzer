package dev.nibin.buzzer.session.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * What a running game needs every second: where the session is and who is in it. Kept in Redis for speed;
 * everything here can be rebuilt from Postgres except the question pointer and deadline, which only exist
 * while a question is running (batch 3.4).
 *
 * @param currentQuestionIndex index into the session's questions; empty in the lobby and after the end
 * @param questionDeadline     when answers for the current question stop being accepted; empty when none runs
 */
public record LiveState(UUID sessionId, Session.Status status, Optional<Integer> currentQuestionIndex,
        Optional<Instant> questionDeadline, List<RosterEntry> players) {

    public LiveState {
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(currentQuestionIndex, "currentQuestionIndex");
        Objects.requireNonNull(questionDeadline, "questionDeadline");
        players = List.copyOf(players);
    }

    /** The live state of a session that nothing has happened to yet, or rebuilt from Postgres. */
    public static LiveState of(UUID sessionId, Session.Status status, List<RosterEntry> players) {
        return new LiveState(sessionId, status, Optional.empty(), Optional.empty(), players);
    }

    /** A player as everyone in the session sees them. */
    public record RosterEntry(UUID playerId, String displayName) {

        public static RosterEntry of(Player player) {
            return new RosterEntry(player.playerId(), player.displayName());
        }
    }
}
