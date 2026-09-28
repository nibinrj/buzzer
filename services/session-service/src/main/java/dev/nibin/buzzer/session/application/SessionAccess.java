package dev.nibin.buzzer.session.application;

import dev.nibin.buzzer.session.domain.PlayerRepository;
import dev.nibin.buzzer.session.domain.SessionRepository;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Who belongs to a session, for the WebSocket destination checks. Asked once per SUBSCRIBE and per host
 * command, not per pushed message, so reading Postgres here is cheap enough.
 */
@Service
public class SessionAccess {

    private final SessionRepository sessions;
    private final PlayerRepository players;

    public SessionAccess(SessionRepository sessions, PlayerRepository players) {
        this.sessions = sessions;
        this.players = players;
    }

    public boolean isHost(UUID sessionId, UUID userId) {
        return sessions.findById(sessionId).filter(session -> session.hostId().equals(userId)).isPresent();
    }

    /** The host, or someone who joined. */
    public boolean isHostOrPlayer(UUID sessionId, UUID userId) {
        return isHost(sessionId, userId) || players.find(sessionId, userId).isPresent();
    }
}
