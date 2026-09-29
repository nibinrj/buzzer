package dev.nibin.buzzer.scoring.application;

import java.util.UUID;

/** Scoring has never seen an event for this session: no answer, no start, no end. */
public class SessionNotFoundException extends RuntimeException {

    public SessionNotFoundException(UUID sessionId) {
        super("no scoring data for session " + sessionId);
    }
}
