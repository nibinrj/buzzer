-- scoring_db. Filled only from Kafka events (session.answer-submitted, session.lifecycle), which arrive at least
-- once and in no guaranteed order across topics or retries. Hence: no foreign keys between these tables (an answer
-- may arrive before its session's start), and every write is an idempotent upsert or an atomic increment.

-- Every event already applied, by its eventId. Inserted in the SAME transaction as the change the event causes,
-- with ON CONFLICT DO NOTHING: 0 rows inserted means "seen before", so the change is skipped.
CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL
);

-- One running total per player per session. Changed only by "points = points + ?", never read-modify-write.
CREATE TABLE player_scores (
    session_id      UUID        NOT NULL,
    player_id       UUID        NOT NULL, -- the player's membership id in the session, not their user id
    points          INTEGER     NOT NULL,
    answers         INTEGER     NOT NULL, -- accepted answers counted so far
    correct_answers INTEGER     NOT NULL,
    updated_at      TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (session_id, player_id)
);

-- What scoring knows about a session's lifecycle. Either event may come first, so every column but the key is
-- nullable. "Ended" is a status (ended_at_ms set), not a frozen copy of the scores: an answer that arrives after
-- SessionEnded (from a retry topic) still counts.
CREATE TABLE scoring_sessions (
    session_id     UUID    PRIMARY KEY,
    question_count INTEGER,         -- from SessionStarted
    started_at_ms  BIGINT,          -- from SessionStarted, epoch ms by session-service's Redis clock
    ended_at_ms    BIGINT           -- from SessionEnded, same clock
);
