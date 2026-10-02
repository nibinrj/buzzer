package dev.nibin.buzzer.session.infrastructure.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/** One row of the outbox table (V3). The id is assigned by Postgres on insert and gives the publish order. */
@Entity
@Table(name = "outbox")
public class OutboxJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false)
    private UUID eventId;

    @Column(name = "topic", nullable = false, updatable = false, length = 100)
    private String topic;

    @Column(name = "message_key", nullable = false, updatable = false, length = 100)
    private String messageKey;

    @Column(name = "event_type", nullable = false, updatable = false, length = 50)
    private String eventType;

    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    /** W3C traceparent of the trace the event was written in (V5); null outside any trace. */
    @Column(name = "traceparent", updatable = false, length = 55)
    private String traceparent;

    /** Required by JPA. */
    protected OutboxJpaEntity() {
    }

    OutboxJpaEntity(UUID eventId, String topic, String messageKey, String eventType, String payload,
            Instant createdAt, String traceparent) {
        this.eventId = eventId;
        this.topic = topic;
        this.messageKey = messageKey;
        this.eventType = eventType;
        this.payload = payload;
        this.createdAt = createdAt;
        this.traceparent = traceparent;
    }

    /** The broker acknowledged it. Written back when the publisher's transaction commits (dirty checking). */
    void markSent(Instant now) {
        this.sentAt = now;
    }

    Long getId() {
        return id;
    }

    UUID getEventId() {
        return eventId;
    }

    String getTopic() {
        return topic;
    }

    String getMessageKey() {
        return messageKey;
    }

    String getEventType() {
        return eventType;
    }

    String getTraceparent() {
        return traceparent;
    }

    String getPayload() {
        return payload;
    }

    Instant getSentAt() {
        return sentAt;
    }
}
