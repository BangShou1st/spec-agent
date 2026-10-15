-- Both execution modes need persisted user delegation; missing tasks are not authorization.
CREATE TABLE ga_run_authorizations (
    run_id UUID PRIMARY KEY REFERENCES global_assistant_runs(id) ON DELETE CASCADE,
    actor_user_id UUID NOT NULL REFERENCES app_users(id),
    actor_org_id UUID NOT NULL REFERENCES organizations(id),
    actor_source VARCHAR(20) NOT NULL CHECK (actor_source IN ('LOCAL','OIDC')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO ga_run_authorizations(run_id,actor_user_id,actor_org_id,actor_source)
SELECT r.id,t.owner_user_id,t.org_id,
       CASE WHEN t.owner_user_id='00000000-0000-4000-8000-000000000002'::uuid
                 AND t.org_id='00000000-0000-4000-8000-000000000001'::uuid
            THEN 'LOCAL' ELSE 'OIDC' END
FROM global_assistant_runs r JOIN global_assistant_threads t ON t.id=r.thread_id;
-- Defaults must not manufacture privileged delegation when an insertion forgets its actor.
ALTER TABLE runtime_tasks ALTER COLUMN actor_user_id DROP DEFAULT;
ALTER TABLE runtime_tasks ALTER COLUMN actor_org_id DROP DEFAULT;
ALTER TABLE runtime_tasks ALTER COLUMN actor_source DROP DEFAULT;
