ALTER TABLE embedding_rebuilds
    ADD COLUMN execution_epoch BIGINT NOT NULL DEFAULT 0 CHECK(execution_epoch>=0),
    ADD COLUMN worker_id UUID,
    ADD COLUMN lease_id UUID,
    ADD COLUMN lease_until TIMESTAMPTZ;
-- Old running derived jobs have no trustworthy owner. Preserve progress and requeue them.
UPDATE embedding_rebuilds SET state='QUEUED',error_code='REBUILD_LEASE_EXPIRED' WHERE state='RUNNING';
ALTER TABLE embedding_rebuilds ADD CONSTRAINT embedding_rebuild_lease_state CHECK (
    (state='RUNNING' AND execution_epoch>0 AND worker_id IS NOT NULL AND lease_id IS NOT NULL AND lease_until IS NOT NULL)
    OR (state<>'RUNNING' AND worker_id IS NULL AND lease_id IS NULL AND lease_until IS NULL)
);
ALTER TABLE retrieval_scope_grants
    ADD COLUMN rebuild_id UUID REFERENCES embedding_rebuilds(id) ON DELETE CASCADE,
    ADD COLUMN rebuild_epoch BIGINT,
    ADD COLUMN rebuild_lease_id UUID;
ALTER TABLE retrieval_scope_grants ADD CONSTRAINT retrieval_rebuild_binding CHECK (
    (rebuild_id IS NULL AND rebuild_epoch IS NULL AND rebuild_lease_id IS NULL)
    OR (workload_kind='INDEX_JOB' AND rebuild_id IS NOT NULL AND rebuild_epoch IS NOT NULL
        AND rebuild_epoch>0 AND rebuild_lease_id IS NOT NULL)
);
CREATE INDEX ix_embedding_rebuild_claim ON embedding_rebuilds(state,lease_until,created_at,id);
