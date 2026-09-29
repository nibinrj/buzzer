package dev.nibin.buzzer.scoring.domain;

import java.util.UUID;

/**
 * What scoring knows about a session. Lifecycle events and answers arrive in any order, so the start fields are
 * null until SessionStarted was read, and endedAtMs is null until SessionEnded was.
 *
 * @param version bumped with every scored answer; 0 before the first
 */
public record ScoringSession(UUID sessionId, Integer questionCount, Long startedAtMs, Long endedAtMs, long version) {

    /** "Ended" is a status, not a frozen copy of the scores: a late answer still counts. */
    public boolean ended() {
        return endedAtMs != null;
    }
}
