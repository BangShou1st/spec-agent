-- Explicit non-null checks are required: CHECK permits UNKNOWN as well as TRUE.
ALTER TABLE retrieval_scope_grants DROP CONSTRAINT retrieval_index_service_required;
ALTER TABLE retrieval_scope_grants ADD CONSTRAINT retrieval_index_service_required CHECK (
    revoked OR workload_kind<>'INDEX_JOB' OR
    (service_kind IS NOT NULL AND (
        (project_id IS NOT NULL AND corpus_id=project_id AND service_kind='SYSTEM_INDEXING' AND service_org_id IS NOT NULL) OR
        (project_id IS NULL AND corpus_id='a71ae401-fff4-3f8a-82f1-023ab37608da'::uuid
            AND service_kind='SYSTEM_HELP_INDEXING' AND service_org_id IS NULL)
    ))
);
ALTER TABLE retrieval_scope_grants ADD CONSTRAINT retrieval_service_project_same_org
    FOREIGN KEY (project_id,service_org_id) REFERENCES projects(id,org_id) ON DELETE CASCADE;
