package dev.nibin.buzzer.session.infrastructure.outbox;

import dev.nibin.buzzer.events.AnswerSubmitted;
import dev.nibin.buzzer.events.SessionEnded;
import dev.nibin.buzzer.events.SessionLifecycle;
import dev.nibin.buzzer.events.SessionStarted;
import dev.nibin.buzzer.session.application.EventOutbox;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.UUID;

/**
 * Writes events to the outbox table as JSON. Which topic, and which key, is decided here: every event is keyed by
 * its session id, so one session's events keep their order on one partition.
 * <p>
 * Propagation.MANDATORY: called without a transaction, it throws instead of quietly starting its own. An outbox row
 * written in a separate transaction from its change is exactly the bug the outbox exists to prevent.
 */
@Component
class JpaEventOutbox implements EventOutbox {

    private final OutboxJpaRepository repository;
    private final JsonMapper json;
    private final Clock clock;

    JpaEventOutbox(OutboxJpaRepository repository, JsonMapper json, Clock clock) {
        this.repository = repository;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void answerSubmitted(AnswerSubmitted event) {
        add(event.eventId(), AnswerSubmitted.TOPIC, event.sessionId(), event);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void sessionStarted(SessionStarted event) {
        add(event.eventId(), SessionLifecycle.TOPIC, event.sessionId(), event);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void sessionEnded(SessionEnded event) {
        add(event.eventId(), SessionLifecycle.TOPIC, event.sessionId(), event);
    }

    private void add(UUID eventId, String topic, UUID sessionId, Record event) {
        repository.save(new OutboxJpaEntity(eventId, topic, sessionId.toString(), event.getClass().getSimpleName(),
                json.writeValueAsString(event), clock.instant()));
    }
}
