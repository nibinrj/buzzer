package dev.nibin.buzzer.session.application;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * The MDC keys this service puts on its log lines, so that a search for one session, user or player finds every
 * line about it. Structured logging (application.yml) writes each MDC entry as a field of the JSON line.
 * <p>
 * Whoever puts a key removes it when its unit of work ends (an HTTP request, a STOMP frame, a Kafka record), with
 * MDC.remove, never MDC.clear: tracing keeps traceId and spanId in the same map. Threads are pooled, so a key left
 * behind would label the next, unrelated piece of work with the wrong id.
 */
public final class LogContext {

    public static final String SESSION_ID = "sessionId";
    /** The authenticated user (the JWT's sub). */
    public static final String USER_ID = "userId";
    /** The user's membership in one session. Known only once a use case has looked it up. */
    public static final String PLAYER_ID = "playerId";

    private LogContext() {
    }

    /**
     * Puts {@code value} under {@code key} if it is a UUID; anything else is left out. The raw values come from
     * clients (a URL, a STOMP destination, a Kafka key): a field named sessionId should only ever hold a session id.
     */
    public static void putIfUuid(String key, String value) {
        if (value == null) {
            return;
        }
        try {
            MDC.put(key, UUID.fromString(value).toString());
        } catch (IllegalArgumentException e) {
            // Not an id: stays out of the log fields.
        }
    }
}
