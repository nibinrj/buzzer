package dev.nibin.buzzer.session.infrastructure.metrics;

import dev.nibin.buzzer.session.domain.SessionRepository;
import dev.nibin.buzzer.session.infrastructure.outbox.OutboxJpaRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * The gauges: current values, read when Prometheus scrapes (not when something happens). Boot binds every
 * MeterBinder bean to the registry by itself.
 * <p>
 * <b>Across instances</b>, two kinds. buzzer.websocket.connections is this instance's own: a dashboard SUMS it.
 * The other three come from Postgres, so every instance reports the same number: a dashboard takes their MAX, never
 * the sum. If Postgres can't be reached, a gauge reads NaN (Micrometer catches the exception) rather than failing
 * the scrape.
 */
@Component
class SessionMeters implements MeterBinder {

    private final SessionRepository sessions;
    private final OutboxJpaRepository outbox;
    private final SimpUserRegistry users;
    private final Clock clock;

    SessionMeters(SessionRepository sessions, OutboxJpaRepository outbox, SimpUserRegistry users, Clock clock) {
        this.sessions = sessions;
        this.outbox = outbox;
        this.users = users;
        this.clock = clock;
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder("buzzer.sessions.active", sessions, SessionRepository::countInProgress)
                .description("Sessions in progress, from Postgres (same on every instance: take the max)")
                .register(registry);
        Gauge.builder("buzzer.websocket.connections", users, SessionMeters::connections)
                .description("STOMP connections open on this instance (sum across instances)")
                .register(registry);
        Gauge.builder("buzzer.outbox.backlog", outbox, OutboxJpaRepository::countBySentAtIsNull)
                .description("Outbox rows not yet acknowledged by Kafka (same on every instance: take the max)")
                .register(registry);
        Gauge.builder("buzzer.outbox.oldest.unsent.age", this, SessionMeters::oldestUnsentAgeSeconds)
                .description("Age of the oldest unsent outbox row, 0 when none (same on every instance)")
                .baseUnit("seconds")
                .register(registry);
    }

    /** STOMP sessions, not users: one player with two tabs is two connections. */
    private static double connections(SimpUserRegistry users) {
        return users.getUsers().stream().mapToInt(user -> user.getSessions().size()).sum();
    }

    private double oldestUnsentAgeSeconds() {
        return outbox.oldestUnsentCreatedAt()
                .map(createdAt -> Duration.between(createdAt, clock.instant()).toMillis() / 1000.0)
                .orElse(0.0);
    }
}
