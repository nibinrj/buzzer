package dev.nibin.buzzer.scoring.infrastructure.kafka;

/**
 * A record that can never be applied, however often it is retried: bad JSON, an unknown or missing eventType, an
 * unsupported schemaVersion, a missing id, or contradicting fields. ScoringEventListener excludes it from retries,
 * so such a record goes straight to the DLT.
 */
public class UnreadableEventException extends RuntimeException {

    public UnreadableEventException(String message) {
        super(message);
    }

    public UnreadableEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
