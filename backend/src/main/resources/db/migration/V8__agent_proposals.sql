-- Action proposal lifecycle tracking for Advisor mode.
-- Proposals are immutable once created; status transitions are recorded
-- through decided_at/decided_by columns for full auditability.
CREATE TABLE agent_proposals (
    id                      UUID PRIMARY KEY,
    run_id                  UUID NOT NULL,
    project_id              UUID NOT NULL,
    route_id                UUID,
    action_family           VARCHAR(64) NOT NULL,
    payload_json            JSONB NOT NULL DEFAULT '{}',
    status                  VARCHAR(32) NOT NULL DEFAULT 'PROPOSED',
    base_context_snapshot_id UUID,
    base_context_hash       VARCHAR(128),
    idempotency_key         VARCHAR(256),
    created_at              TIMESTAMP NOT NULL DEFAULT NOW(),
    decided_at              TIMESTAMP,
    decided_by              VARCHAR(128)
);

CREATE INDEX idx_agent_proposals_project_status ON agent_proposals (project_id, status);
CREATE UNIQUE INDEX idx_agent_proposals_idempotency_key ON agent_proposals (idempotency_key) WHERE idempotency_key IS NOT NULL;
