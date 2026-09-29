package dev.nibin.buzzer.scoring.infrastructure.kafka;

import dev.nibin.buzzer.events.AnswerSubmitted;
import dev.nibin.buzzer.events.EventHeaders;
import dev.nibin.buzzer.events.SessionEnded;
import dev.nibin.buzzer.events.SessionLifecycle;
import dev.nibin.buzzer.events.SessionStarted;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventReaderTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final EventReader reader = new EventReader(json);

    private final UUID session = UUID.randomUUID();

    @Test
    void readsEachEventTypeByItsHeader() {
        AnswerSubmitted answer = answer(true, 1);
        SessionStarted started = new SessionStarted(UUID.randomUUID(), session, UUID.randomUUID(),
                UUID.randomUUID(), 5, 1_000L, SessionStarted.SCHEMA_VERSION);
        SessionEnded ended = new SessionEnded(UUID.randomUUID(), session, 2_000L, SessionEnded.SCHEMA_VERSION);

        assertThat(reader.read(record(AnswerSubmitted.TOPIC, "AnswerSubmitted", json.writeValueAsString(answer))))
                .isEqualTo(answer);
        assertThat(reader.read(record(SessionLifecycle.TOPIC, "SessionStarted", json.writeValueAsString(started))))
                .isEqualTo(started);
        assertThat(reader.read(record(SessionLifecycle.TOPIC, "SessionEnded", json.writeValueAsString(ended))))
                .isEqualTo(ended);
    }

    @Test
    void fieldsANewerProducerAddsAreIgnored() {
        String withExtra = json.writeValueAsString(answer(false, 0)).replaceFirst("\\{", "{\"addedLater\":42,");

        assertThat(reader.read(record(AnswerSubmitted.TOPIC, "AnswerSubmitted", withExtra)))
                .isInstanceOf(AnswerSubmitted.class);
    }

    @Test
    void badJsonIsUnreadableAndTheMessageDoesNotQuoteIt() {
        assertThatThrownBy(() -> reader.read(record(AnswerSubmitted.TOPIC, "AnswerSubmitted", "{\"secret\": ")))
                .isInstanceOf(UnreadableEventException.class)
                .hasMessageStartingWith("not a valid AnswerSubmitted")
                .hasMessageNotContaining("secret");
    }

    @Test
    void aMissingOrUnknownTypeHeaderIsUnreadable() {
        String body = json.writeValueAsString(answer(true, 1));

        assertThatThrownBy(() -> reader.read(record(AnswerSubmitted.TOPIC, null, body)))
                .isInstanceOf(UnreadableEventException.class).hasMessage("no eventType header");
        assertThatThrownBy(() -> reader.read(record(AnswerSubmitted.TOPIC, "AnswerDeleted", body)))
                .isInstanceOf(UnreadableEventException.class).hasMessage("unknown eventType 'AnswerDeleted'");
    }

    @Test
    void anUnsupportedSchemaVersionIsUnreadable() {
        AnswerSubmitted v2 = new AnswerSubmitted(UUID.randomUUID(), session, UUID.randomUUID(), UUID.randomUUID(),
                0, true, 1, 1, 1_000L, 2);

        assertThatThrownBy(() -> reader.read(record(AnswerSubmitted.TOPIC, "AnswerSubmitted",
                json.writeValueAsString(v2))))
                .isInstanceOf(UnreadableEventException.class)
                .hasMessage("AnswerSubmitted schemaVersion 2, only 1 supported");
    }

    @Test
    void aMissingIdIsUnreadable() {
        AnswerSubmitted noPlayer = new AnswerSubmitted(UUID.randomUUID(), session, UUID.randomUUID(), null,
                0, true, 1, 1, 1_000L, AnswerSubmitted.SCHEMA_VERSION);

        assertThatThrownBy(() -> reader.read(record(AnswerSubmitted.TOPIC, "AnswerSubmitted",
                json.writeValueAsString(noPlayer))))
                .isInstanceOf(UnreadableEventException.class).hasMessage("no playerId");
    }

    @Test
    void correctAndCorrectRankMustAgree() {
        for (AnswerSubmitted contradicting : new AnswerSubmitted[] {answer(true, 0), answer(false, 3)}) {
            assertThatThrownBy(() -> reader.read(record(AnswerSubmitted.TOPIC, "AnswerSubmitted",
                    json.writeValueAsString(contradicting))))
                    .isInstanceOf(UnreadableEventException.class)
                    .hasMessageContaining("correctRank=" + contradicting.correctRank());
        }
    }

    @Test
    void aRecordWithoutAValueIsUnreadable() {
        assertThatThrownBy(() -> reader.read(record(SessionLifecycle.TOPIC, "SessionEnded", null)))
                .isInstanceOf(UnreadableEventException.class).hasMessage("no value for SessionEnded");
    }

    private AnswerSubmitted answer(boolean correct, int correctRank) {
        return new AnswerSubmitted(UUID.randomUUID(), session, UUID.randomUUID(), UUID.randomUUID(), 0, correct,
                correctRank, 1, 1_000L, AnswerSubmitted.SCHEMA_VERSION);
    }

    private ConsumerRecord<String, String> record(String topic, String type, String value) {
        ConsumerRecord<String, String> record = new ConsumerRecord<>(topic, 0, 0L, session.toString(), value);
        if (type != null) {
            record.headers().add(EventHeaders.TYPE, type.getBytes(StandardCharsets.UTF_8));
        }
        return record;
    }
}
