package dev.nibin.buzzer.events;

/**
 * The session.lifecycle topic carries two event types, SessionStarted and SessionEnded, on one topic so that one
 * session's start and end stay in order (same key, same partition). The header {@value EventHeaders#TYPE} tells
 * them apart.
 */
public final class SessionLifecycle {

    public static final String TOPIC = "session.lifecycle";

    private SessionLifecycle() {
    }
}
