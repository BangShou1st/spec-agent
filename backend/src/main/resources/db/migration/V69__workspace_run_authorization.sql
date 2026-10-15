CREATE TABLE workspace_run_authorizations (
    run_id UUID PRIMARY KEY REFERENCES agent_runs(id) ON DELETE CASCADE,
    actor_user_id UUID NOT NULL REFERENCES app_users(id),
    actor_org_id UUID NOT NULL REFERENCES organizations(id),
    actor_source VARCHAR(20) NOT NULL CHECK (actor_source IN ('LOCAL','OIDC')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Only reconstruct a delegation from an existing, explicit persisted task fact.
-- Project creators, organization roles and missing actors are not substitutes for a Run initiator.
INSERT INTO workspace_run_authorizations(run_id,actor_user_id,actor_org_id,actor_source)
SELECT t.workspace_run_id,t.actor_user_id,t.actor_org_id,t.actor_source
FROM runtime_tasks t WHERE t.workspace_run_id IS NOT NULL
    AND t.actor_user_id IS NOT NULL AND t.actor_org_id IS NOT NULL
    AND t.actor_source IN ('LOCAL','OIDC');

CREATE INDEX idx_workspace_run_authorizations_actor ON workspace_run_authorizations(actor_user_id,actor_org_id);
