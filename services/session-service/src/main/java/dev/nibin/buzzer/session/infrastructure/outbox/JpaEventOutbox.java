package dev.nibin.buzzer.session.infrastructure.outbox;

import dev.nibin.buzzer.events.AnswerSubmitted;
import dev.nibin.buzzer.events.SessionEnded;
import dev.nibin.buzzer.events.SessionLifecycle;
import dev.nibin.buzzer.events.SessionStarted;
import dev.nibin.buzzer.session.application.EventOutbox;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
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

    /** The W3C Trace Context header (and outbox column) name. */
    static final String TRACEPARENT = "traceparent";

    private final OutboxJpaRepository repository;
    private final JsonMapper json;
    private final Clock clock;
    private final Tracer tracer;
    private final Propagator propagator;

    JpaEventOutbox(OutboxJpaRepository repository, JsonMapper json, Clock clock, Tracer tracer,
            Propagator propagator) {
        this.repository = repository;
        this.json = json;
        this.clock = clock;
        this.tracer = tracer;
        this.propagator = propagator;
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
                json.writeValueAsString(event), clock.instant(), currentTraceparent()));
    }

    /**
     * The current trace (the STOMP command's, see StompObservationInterceptor) in W3C form, or null outside a
     * trace. Written by the configured propagator, exactly as an HTTP or Kafka header would be, rather than
     * formatted by hand: the sampled flag travels with it, so the publisher keeps the original sampling decision.
     */
    private String currentTraceparent() {
        TraceContext context = tracer.currentTraceContext().context();
        if (context == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(context, carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }
}
