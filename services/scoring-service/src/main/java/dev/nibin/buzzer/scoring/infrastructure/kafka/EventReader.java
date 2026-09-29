package dev.nibin.buzzer.scoring.infrastructure.kafka;

import dev.nibin.buzzer.events.AnswerSubmitted;
import dev.nibin.buzzer.events.EventHeaders;
import dev.nibin.buzzer.events.SessionEnded;
import dev.nibin.buzzer.events.SessionStarted;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;

/**
 * Turns a record's String value into the event record its {@value EventHeaders#TYPE} header names, and refuses
 * anything scoring can't apply. Every refusal is an {@link UnreadableEventException}: retrying can't fix it, so it
 * goes straight to the DLT. Messages name fields and types only, never the record's contents.
 * <p>
 * Parsed with Boot's JsonMapper (Jackson 3), which ignores unknown properties: a newer producer may add fields to
 * the same schemaVersion without breaking this consumer.
 */
@Component
public class EventReader {

    private final JsonMapper json;

    public EventReader(JsonMapper json) {
        this.json = json;
    }

    /** @return an {@link AnswerSubmitted}, {@link SessionStarted} or {@link SessionEnded} */
    public Object read(ConsumerRecord<String, String> record) {
        String type = typeOf(record);
        return switch (type) {
            case "AnswerSubmitted" -> checked(parse(record, AnswerSubmitted.class));
            case "SessionStarted" -> checked(parse(record, SessionStarted.class));
            case "SessionEnded" -> checked(parse(record, SessionEnded.class));
            default -> throw new UnreadableEventException("unknown " + EventHeaders.TYPE + " '" + type + "'");
        };
    }

    private static String typeOf(ConsumerRecord<String, String> record) {
        Header header = record.headers().lastHeader(EventHeaders.TYPE);
        if (header == null || header.value() == null) {
            throw new UnreadableEventException("no " + EventHeaders.TYPE + " header");
        }
        return new String(header.value(), StandardCharsets.UTF_8);
    }

    private <T> T parse(ConsumerRecord<String, String> record, Class<T> type) {
        if (record.value() == null) {
            throw new UnreadableEventException("no value for " + type.getSimpleName());
        }
        try {
            return json.readValue(record.value(), type);
        } catch (JacksonException e) {
            // Only the exception's type in the message: Jackson's own message quotes the input.
            throw new UnreadableEventException(
                    "not a valid " + type.getSimpleName() + " (" + e.getClass().getSimpleName() + ")", e);
        }
    }

    private static AnswerSubmitted checked(AnswerSubmitted event) {
        version(event.schemaVersion(), AnswerSubmitted.SCHEMA_VERSION, "AnswerSubmitted");
        present(event.eventId(), "eventId");
        present(event.sessionId(), "sessionId");
        present(event.questionId(), "questionId");
        present(event.playerId(), "playerId");
        if (event.correct() ? event.correctRank() < 1 : event.correctRank() != 0) {
            throw new UnreadableEventException("AnswerSubmitted with correct=" + event.correct()
                    + " and correctRank=" + event.correctRank());
        }
        return event;
    }

    private static SessionStarted checked(SessionStarted event) {
        version(event.schemaVersion(), SessionStarted.SCHEMA_VERSION, "SessionStarted");
        present(event.eventId(), "eventId");
        present(event.sessionId(), "sessionId");
        return event;
    }

    private static SessionEnded checked(SessionEnded event) {
        version(event.schemaVersion(), SessionEnded.SCHEMA_VERSION, "SessionEnded");
        present(event.eventId(), "eventId");
        present(event.sessionId(), "sessionId");
        return event;
    }

    private static void version(int actual, int supported, String type) {
        if (actual != supported) {
            throw new UnreadableEventException(type + " schemaVersion " + actual + ", only " + supported + " supported");
        }
    }

    private static void present(Object value, String field) {
        if (value == null) {
            throw new UnreadableEventException("no " + field);
        }
    }
}
