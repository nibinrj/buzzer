CREATE TABLE users (
    id            UUID         PRIMARY KEY,
    email         VARCHAR(320) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL
);

-- Case-insensitive uniqueness, independent of how the application normalizes emails.
CREATE UNIQUE INDEX users_email_lower_uk ON users (lower(email));

CREATE TABLE user_roles (
    user_id UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    role    VARCHAR(20) NOT NULL CHECK (role IN ('HOST', 'PLAYER')),
    PRIMARY KEY (user_id, role)
);
