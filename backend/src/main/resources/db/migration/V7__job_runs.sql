-- When each once-a-day job last ran, so restarts and Render's sleeps can't make it
-- run twice in a day (SPEC-v2 §6).
CREATE TABLE job_runs (
  name         VARCHAR(40) PRIMARY KEY,   -- 'cleanup'
  last_run_at  TIMESTAMPTZ NOT NULL
);
