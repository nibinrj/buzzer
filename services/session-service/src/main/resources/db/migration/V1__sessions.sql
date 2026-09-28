-- A live session and the frozen copy of the quiz it plays. Copied once at creation from quiz-service's
-- snapshot, never updated: a game must not change under the players, and quiz-service may be down later.

CREATE TABLE sessions (
    id         UUID         PRIMARY KEY,
    -- 6 chars from A-Z minus I and O, plus 2-9 (no 0/O, 1/I). Unique across all sessions, ended ones included,
    -- so a room code always names exactly one session. JpaSessionRepositoryAdapter catches this constraint by name.
    room_code  VARCHAR(6)   NOT NULL CONSTRAINT sessions_room_code_uk UNIQUE
                            CHECK (room_code ~ '^[A-HJ-NP-Z2-9]{6}$'),
    quiz_id    UUID         NOT NULL, -- quiz-service id; no FK: another service's database
    host_id    UUID         NOT NULL, -- identity-service user id (JWT sub)
    quiz_title VARCHAR(120) NOT NULL,
    status     VARCHAR(20)  NOT NULL CHECK (status IN ('LOBBY', 'IN_PROGRESS', 'ENDED')),
    created_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX sessions_host_id_idx ON sessions (host_id);

-- One row per question, in play order. Options are values without identity (an option's id is its index),
-- so they are an array on the question rather than a table of their own.
CREATE TABLE session_questions (
    session_id         UUID           NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    position           INT            NOT NULL CHECK (position BETWEEN 0 AND 49),
    question_id        UUID           NOT NULL, -- quiz-service's question id, used by answers and events
    text               VARCHAR(300)   NOT NULL,
    time_limit_seconds INT            NOT NULL CHECK (time_limit_seconds BETWEEN 5 AND 60),
    options            VARCHAR(120)[] NOT NULL CHECK (cardinality(options) BETWEEN 2 AND 6),
    correct_option     INT            NOT NULL,
    PRIMARY KEY (session_id, position),
    CONSTRAINT session_questions_question_uk UNIQUE (session_id, question_id),
    CONSTRAINT session_questions_correct_option_ck CHECK (correct_option >= 0 AND correct_option < cardinality(options))
);

-- Filled by joins (batch 3.3). Created now so the whole session schema is in V1.
CREATE TABLE players (
    id           UUID        PRIMARY KEY,
    session_id   UUID        NOT NULL REFERENCES sessions (id) ON DELETE CASCADE,
    user_id      UUID        NOT NULL, -- identity-service user id (JWT sub; guests have one too)
    display_name VARCHAR(30) NOT NULL CHECK (btrim(display_name) <> ''),
    joined_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT players_session_user_uk UNIQUE (session_id, user_id)
);
