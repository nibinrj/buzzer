-- buzzer.sessions.active counts IN_PROGRESS sessions on every Prometheus scrape (every 15 s, on every instance).
-- The sessions table only grows; a partial index holds just the running games, so the count reads a handful of
-- index entries instead of scanning every session ever played.
CREATE INDEX sessions_in_progress_idx ON sessions (id) WHERE status = 'IN_PROGRESS';
