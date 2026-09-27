-- One row per refresh token ever issued. The raw token is never stored, only its SHA-256 (hex).
-- A family is one login session: every rotation adds a row with the same family_id.
CREATE TABLE refresh_tokens (
    id         UUID        PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    family_id  UUID        NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    revoked_at TIMESTAMPTZ,
    CONSTRAINT refresh_tokens_token_hash_uk UNIQUE (token_hash),
    CONSTRAINT refresh_tokens_token_hash_format_ck CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT refresh_tokens_expiry_ck CHECK (expires_at > created_at)
);

-- The unique constraint already indexes token_hash (lookup on every refresh).
CREATE INDEX refresh_tokens_family_id_idx ON refresh_tokens (family_id);  -- revoke a whole family
CREATE INDEX refresh_tokens_user_id_idx ON refresh_tokens (user_id);      -- FK: ON DELETE CASCADE lookups
