-- Transactional outbox: an event to publish is written here IN THE SAME TRANSACTION as the change it describes
-- (the answer row, the session's status). So the event exists if and only if the change committed. OutboxPublisher
-- then sends unsent rows to Kafka in id order and stamps sent_at once the broker acknowledged them.

CREATE TABLE outbox (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY, -- publish order
    event_id    UUID         NOT NULL CONSTRAINT outbox_event_uk UNIQUE,
    topic       VARCHAR(100) NOT NULL,
    message_key VARCHAR(100) NOT NULL,                                 -- the session id: one partition per session
    event_type  VARCHAR(50)  NOT NULL,                                 -- sent as the eventType header
    payload     TEXT         NOT NULL,                                 -- the event record as JSON
    created_at  TIMESTAMPTZ  NOT NULL,
    sent_at     TIMESTAMPTZ                                            -- null until the broker acknowledged it
);

-- The publisher's only query: the oldest unsent rows. A partial index stays as small as the backlog.
CREATE INDEX outbox_unsent_idx ON outbox (id) WHERE sent_at IS NULL;
