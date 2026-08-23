-- Capability foundation: durable invocation log.
-- The Runtime owns retry/idempotency metadata: a capability invocation is
-- keyed by a runtime-assigned invocation key; replays return the recorded
-- result instead of re-executing side effects.
CREATE TABLE capability_invocations (
    id              UUID PRIMARY KEY,
    invocation_key  VARCHAR(256) NOT NULL,
    project_id      UUID NOT NULL REFERENCES projects(id),
    run_id          UUID,
    capability_id   VARCHAR(120) NOT NULL,
    arguments       JSONB NOT NULL DEFAULT '{}',
    status          VARCHAR(20) NOT NULL,
    result          JSONB,
    created_at      TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMP
);

-- Idempotency: one recorded execution per invocation key.
CREATE UNIQUE INDEX idx_capability_invocations_key ON capability_invocations (invocation_key);
CREATE INDEX idx_capability_invocations_project ON capability_invocations (project_id, created_at);
