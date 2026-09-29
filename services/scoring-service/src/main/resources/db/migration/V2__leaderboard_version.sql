-- The leaderboard's version: one counter per session, bumped in the SAME transaction as every scored answer.
-- ScoreUpdated carries it, and consumers drop anything older than what they already showed. It lives in Postgres,
-- not Redis, because it must never go backwards: a Redis counter would restart at 0 after Redis lost its data,
-- and every later update would then look older than the last one shown.
ALTER TABLE scoring_sessions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
