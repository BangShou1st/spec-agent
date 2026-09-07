-- Slice 3C: durable continuation outbox.
--
-- A terminal run and its continuation-check request commit in ONE
-- transaction (see AgentRunTerminalizationService). The worker's afterCommit
-- dispatch is only the low-latency fast path; a recovery scanner replays any
-- check row left unprocessed after a crash. No semantic fields: the
-- coordinator re-reads durable run facts, never this table, to decide.
CREATE TABLE IF NOT EXISTS agent_run_continuation_checks (
    run_id UUID PRIMARY KEY REFERENCES agent_runs(id),
    requested_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    processed_at TIMESTAMP NULL
);
CREATE INDEX IF NOT EXISTS idx_continuation_checks_pending
    ON agent_run_continuation_checks (requested_at)
    WHERE processed_at IS NULL;
