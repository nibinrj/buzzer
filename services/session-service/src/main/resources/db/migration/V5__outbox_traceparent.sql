-- The trace an event was written in, as a W3C traceparent ("00-<trace id>-<span id>-<flags>", 55 characters).
-- OutboxPublisher runs later, on its own thread, where that trace no longer exists: it restores it from here, so the
-- Kafka record (and everything scoring-service does with it) joins the trace of the answer that caused it.
-- Nullable: rows written before this column, or outside any trace, are published as new traces.
ALTER TABLE outbox ADD COLUMN traceparent VARCHAR(55);
