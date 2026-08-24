-- Post-cutover hardening: client-driven create-run idempotency.
--
-- The async production API accepts mutation runs (ANSWER_TIP, RESUME_ANSWER,
-- DRAFT_QUESTION, GENERATE_ARTIFACT, REGENERATE_NODE). When a client's HTTP
-- response is lost after the run was persisted, a retry with the same
-- idempotency key must return the existing run instead of creating a second
-- one. The unique constraint is the final arbiter; creation is an atomic
-- insert-if-absent, never check-then-insert.

ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(128);
CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_runs_idempotency_key
    ON agent_runs (idempotency_key)
    WHERE idempotency_key IS NOT NULL;
