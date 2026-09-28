package dev.nibin.buzzer.session.domain;

/** The session already has {@link Session#MAX_PLAYERS} players. */
public class SessionFullException extends RuntimeException {

    public SessionFullException() {
        super("Session is full (" + Session.MAX_PLAYERS + " players)");
    }
}
