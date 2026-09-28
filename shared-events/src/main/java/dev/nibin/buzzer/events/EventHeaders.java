package dev.nibin.buzzer.events;

/**
 * Kafka record headers every event carries, on every topic.
 * <p>
 * {@value #TYPE} names the event's record type (its simple class name, e.g. "AnswerSubmitted"), so a consumer knows
 * which record to read the JSON value as. It matters most on session.lifecycle, which carries two types.
 */
public final class EventHeaders {

    public static final String TYPE = "eventType";

    private EventHeaders() {
    }
}
