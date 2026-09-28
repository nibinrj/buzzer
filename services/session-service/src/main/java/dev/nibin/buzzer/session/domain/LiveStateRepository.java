package dev.nibin.buzzer.session.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * Port for the live state (Redis). Every write refreshes the keys' time-to-live. Postgres stays the source of
 * record: anything missing here can be rebuilt from it with {@link #restore}.
 */
public interface LiveStateRepository {

    /** A brand-new session: status, no players yet. */
    void initialize(UUID sessionId, Session.Status status);

    /** Adds (or re-adds, harmlessly) one player to the roster. */
    void addPlayer(UUID sessionId, LiveState.RosterEntry player);

    /** Empty if the session has no live state (never written, expired, or Redis was emptied). */
    Optional<LiveState> find(UUID sessionId);

    /**
     * Writes back state rebuilt from Postgres WITHOUT overwriting anything written meanwhile: roster entries are
     * only added, and the status only set if none exists. A join or a start racing with the rebuild wins.
     */
    void restore(LiveState rebuilt);

    /**
     * "Now" by the live state's own clock (Redis's TIME). Deadlines are computed from this, not from this JVM's
     * clock, because the answer check (batch 4.2) compares against Redis's clock inside a Lua script: one clock
     * for both sides, whatever the app servers' clocks say.
     */
    Instant serverTime();

    /** Status IN_PROGRESS, question {@code index} running until {@code deadline}, open for answers. */
    void showQuestion(UUID sessionId, int index, Instant deadline);

    /**
     * The current question takes no more answers (reveal). The pointer and deadline stay: the question is still the
     * current one, only closed. An answer racing this is decided by Redis's order: before it counts, after it is
     * CLOSED.
     */
    void closeQuestion(UUID sessionId);

    /** Status ENDED; no question runs any more. */
    void end(UUID sessionId);
}
