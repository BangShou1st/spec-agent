-- Scope client create-run idempotency to the project and record the logical
-- request identity used to distinguish a replay from key reuse.
ALTER TABLE agent_runs
    ADD COLUMN IF NOT EXISTS request_fingerprint VARCHAR(64);

DROP INDEX IF EXISTS idx_agent_runs_idempotency_key;

CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_runs_project_idempotency_key
    ON agent_runs (project_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
