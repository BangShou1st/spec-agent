CREATE TABLE workspace_rpc_grants (
    id UUID PRIMARY KEY,
    token_hash CHAR(64) NOT NULL UNIQUE,
    run_id UUID NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    actor_user_id UUID NOT NULL REFERENCES app_users(id),
    actor_org_id UUID NOT NULL REFERENCES organizations(id),
    actor_source VARCHAR(20) NOT NULL CHECK(actor_source IN ('LOCAL','OIDC')),
    audience VARCHAR(40) NOT NULL CHECK(audience='workspace-brain.v1'),
    purpose VARCHAR(40) NOT NULL CHECK(purpose='MODEL_INFERENCE'),
    call_type VARCHAR(30) NOT NULL CHECK(call_type IN ('STATE_UPDATE','DECISION','ARTIFACT_GENERATION')),
    maximum_calls INTEGER NOT NULL CHECK(maximum_calls BETWEEN 1 AND 2),
    consumed_calls INTEGER NOT NULL DEFAULT 0 CHECK(consumed_calls BETWEEN 0 AND maximum_calls),
    task_id UUID REFERENCES runtime_tasks(id) ON DELETE CASCADE,
    task_epoch BIGINT,
    task_lease_id UUID,
    task_worker_id UUID,
    executor_epoch BIGINT NOT NULL,
    executor_pid INTEGER,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK((task_id IS NULL AND task_epoch IS NULL AND task_lease_id IS NULL AND task_worker_id IS NULL)
       OR (task_id IS NOT NULL AND task_epoch>0 AND task_lease_id IS NOT NULL AND task_worker_id IS NOT NULL))
);
CREATE TABLE workspace_rpc_calls (
    grant_id UUID NOT NULL REFERENCES workspace_rpc_grants(id) ON DELETE CASCADE,
    request_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK(status IN ('RESERVED','SUCCEEDED','UNKNOWN')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at TIMESTAMPTZ,
    PRIMARY KEY(grant_id,request_hash)
);
