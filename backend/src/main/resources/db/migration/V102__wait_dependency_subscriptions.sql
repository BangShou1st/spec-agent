-- V4 projection is frozen per run, without reinterpreting existing V2/V3 snapshots.
CREATE TABLE agent_run_dependency_projections (
    run_id UUID PRIMARY KEY REFERENCES agent_runs(id) ON DELETE CASCADE,
    snapshot_id UUID NOT NULL REFERENCES context_snapshots(id),
    payload TEXT NOT NULL,
    payload_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE agent_run_wait_subscriptions (
    wait_run_id UUID PRIMARY KEY REFERENCES agent_runs(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    invocation_id UUID NOT NULL REFERENCES capability_invocations(id) ON DELETE CASCADE,
    snapshot_id UUID NOT NULL REFERENCES context_snapshots(id),
    proposal_id UUID NOT NULL,
    state VARCHAR(16) NOT NULL CHECK (state IN ('WAITING', 'READY', 'INVALIDATED')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX idx_wait_subscriptions_invocation
    ON agent_run_wait_subscriptions(invocation_id) WHERE state = 'WAITING';
