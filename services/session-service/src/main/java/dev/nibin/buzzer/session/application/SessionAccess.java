package dev.nibin.buzzer.session.application;

import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Who belongs to a session, for the WebSocket destination checks. Asked once per SUBSCRIBE, per host command and
 * per answer, not per pushed message. Answered from SessionFacts: per answer, a Postgres read here was one of the
 * connections that made the pool the bottleneck at 200 players (docs/performance.md, H1).
 */
@Service
public class SessionAccess {

    private final SessionFacts facts;

    public SessionAccess(SessionFacts facts) {
        this.facts = facts;
    }

    public boolean isHost(UUID sessionId, UUID userId) {
        return facts.hostOf(sessionId).filter(userId::equals).isPresent();
    }

    /** Someone who joined. The host is not a player of their own session. */
    public boolean isPlayer(UUID sessionId, UUID userId) {
        return facts.player(sessionId, userId).isPresent();
    }

    /** The host, or someone who joined. */
    public boolean isHostOrPlayer(UUID sessionId, UUID userId) {
        return isHost(sessionId, userId) || isPlayer(sessionId, userId);
    }
}
