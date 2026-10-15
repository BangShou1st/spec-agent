ALTER TABLE retrieval_scope_grants
    ADD COLUMN actor_user_id UUID REFERENCES app_users(id),
    ADD COLUMN actor_org_id UUID REFERENCES organizations(id),
    ADD COLUMN actor_source VARCHAR(20) CHECK (actor_source IN ('LOCAL','OIDC')),
    ADD COLUMN execution_token JSONB;

-- Expiring old search grants cannot be safely attributed to a user after the fact.
UPDATE retrieval_scope_grants SET revoked=TRUE WHERE workload_kind <> 'INDEX_JOB';
ALTER TABLE retrieval_scope_grants ADD CONSTRAINT retrieval_search_actor_required CHECK (
    revoked OR workload_kind='INDEX_JOB' OR
    (actor_user_id IS NOT NULL AND actor_org_id IS NOT NULL AND actor_source IS NOT NULL)
);
