package dev.nibin.buzzer.session.domain;

/** The session has started or ended; only players already in it can come back. */
public class SessionNotJoinableException extends RuntimeException {

    public SessionNotJoinableException(Session.Status status) {
        super("Session is " + status + ", no new players");
    }
}
