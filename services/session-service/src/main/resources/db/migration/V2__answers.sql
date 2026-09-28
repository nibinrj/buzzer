-- One row per ACCEPTED answer. The race itself is decided in Redis (submit_answer.lua); this is the durable record
-- of its verdict: arrival order (seq), place among correct answers (correct_rank), and Redis's time of acceptance.
-- Rejected answers (duplicate, late, closed...) leave no row.

CREATE TABLE answers (
    id           UUID        PRIMARY KEY,
    session_id   UUID        NOT NULL,
    question_id  UUID        NOT NULL,
    player_id    UUID        NOT NULL REFERENCES players (id) ON DELETE CASCADE, -- the membership, not the user
    option_index INT         NOT NULL CHECK (option_index >= 0),
    correct      BOOLEAN     NOT NULL,
    seq          BIGINT      NOT NULL CHECK (seq >= 1),
    correct_rank INT         NOT NULL CHECK (correct_rank >= 0), -- 0 = not correct
    answered_at  TIMESTAMPTZ NOT NULL,
    -- The question must be one of this session's (uses session_questions_question_uk).
    CONSTRAINT answers_question_fk FOREIGN KEY (session_id, question_id)
        REFERENCES session_questions (session_id, question_id) ON DELETE CASCADE,
    -- Safety nets behind the script: whatever happens in Redis, one answer per player per question, and no seq
    -- handed out twice. Their indexes also serve every lookup by (session, question).
    CONSTRAINT answers_player_uk UNIQUE (session_id, question_id, player_id),
    CONSTRAINT answers_seq_uk UNIQUE (session_id, question_id, seq),
    CONSTRAINT answers_rank_ck CHECK (correct = (correct_rank > 0))
);
