ALTER TABLE retrieval_scope_grants
    ADD COLUMN service_kind VARCHAR(32) CHECK (service_kind IN ('SYSTEM_INDEXING','SYSTEM_HELP_INDEXING')),
    ADD COLUMN service_org_id UUID REFERENCES organizations(id);

-- Old jobs have no persisted organization/service scope; do not invent it retroactively.
UPDATE retrieval_scope_grants SET revoked=TRUE WHERE workload_kind='INDEX_JOB';
UPDATE retrieval_index_jobs SET state='STALE' WHERE state='CLAIMED';
ALTER TABLE retrieval_scope_grants ADD CONSTRAINT retrieval_index_service_required CHECK (
    revoked OR workload_kind<>'INDEX_JOB' OR
    (project_id IS NOT NULL AND service_kind='SYSTEM_INDEXING' AND service_org_id IS NOT NULL) OR
    (project_id IS NULL AND service_kind='SYSTEM_HELP_INDEXING' AND service_org_id IS NULL)
);
ALTER TABLE retrieval_scope_grants ADD CONSTRAINT retrieval_service_not_user_grant CHECK (
    workload_kind='INDEX_JOB' OR (service_kind IS NULL AND service_org_id IS NULL)
);
