package dev.nibin.buzzer.scoring.application;

import java.util.UUID;

/** Final results were asked for before scoring read the session's SessionEnded. */
public class SessionNotEndedException extends RuntimeException {

    public SessionNotEndedException(UUID sessionId) {
        super("session " + sessionId + " has not ended");
    }
}
