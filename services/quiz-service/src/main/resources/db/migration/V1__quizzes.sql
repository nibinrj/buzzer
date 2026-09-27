-- A quiz aggregate: quizzes 1-n questions 1-n question_options. Limits mirror the domain's
-- always-enforced rules (the publish rules live only in the domain).

CREATE TABLE quizzes (
    id       UUID         PRIMARY KEY,
    owner_id UUID         NOT NULL, -- identity-service user id (JWT sub); no FK: another service's database
    title    VARCHAR(120) NOT NULL CHECK (btrim(title) <> ''),
    status   VARCHAR(20)  NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED')),
    version  BIGINT       NOT NULL -- optimistic locking for the whole aggregate, see JpaQuizRepositoryAdapter
);

CREATE INDEX quizzes_owner_id_idx ON quizzes (owner_id);

CREATE TABLE questions (
    id                 UUID         PRIMARY KEY,
    quiz_id            UUID         NOT NULL REFERENCES quizzes (id) ON DELETE CASCADE,
    position           INT          NOT NULL CHECK (position BETWEEN 0 AND 49),
    text               VARCHAR(300) NOT NULL CHECK (btrim(text) <> ''),
    time_limit_seconds INT          NOT NULL CHECK (time_limit_seconds BETWEEN 5 AND 60),
    -- Checked at commit, not per statement: moving questions shifts positions one row at a time,
    -- and in between two rows can briefly share a position.
    CONSTRAINT questions_quiz_position_uk UNIQUE (quiz_id, position) DEFERRABLE INITIALLY DEFERRED
);
-- No separate index on quiz_id: the unique constraint's index starts with quiz_id and serves those lookups.

CREATE TABLE question_options (
    question_id UUID         NOT NULL REFERENCES questions (id) ON DELETE CASCADE,
    position    INT          NOT NULL CHECK (position BETWEEN 0 AND 5),
    text        VARCHAR(120) NOT NULL CHECK (btrim(text) <> ''),
    correct     BOOLEAN      NOT NULL,
    PRIMARY KEY (question_id, position)
);
