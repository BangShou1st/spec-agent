-- Readiness is distinct from activation. A retry reuses the generation UUID but
-- starts a new readiness attempt; execution_epoch is only a per-batch lease.
ALTER TABLE embedding_rebuilds ADD COLUMN readiness_revision BIGINT NOT NULL DEFAULT 0
    CHECK (readiness_revision >= 0);
CREATE TABLE embedding_rebuild_outcomes (
    generation_id UUID NOT NULL REFERENCES embedding_rebuilds(id) ON DELETE CASCADE,
    readiness_revision BIGINT NOT NULL CHECK (readiness_revision >= 0),
    org_id UUID NOT NULL,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    state VARCHAR(16) NOT NULL CHECK (state IN ('READY','ACTIVE','FAILED','DISCARDED')),
    PRIMARY KEY (generation_id, readiness_revision)
);
-- Existing terminal project jobs can be observed without rerunning work.
INSERT INTO embedding_rebuild_outcomes
SELECT id,readiness_revision,org_id,project_id,state FROM embedding_rebuilds
WHERE project_id IS NOT NULL AND corpus_id=project_id
  AND state IN ('READY','ACTIVE','FAILED','DISCARDED');
CREATE TABLE capability_rebuild_waits (
    invocation_id UUID PRIMARY KEY REFERENCES capability_invocations(id) ON DELETE CASCADE,
    generation_id UUID NOT NULL REFERENCES embedding_rebuilds(id) ON DELETE CASCADE,
    readiness_revision BIGINT NOT NULL CHECK (readiness_revision >= 0),
    last_checked_at TIMESTAMPTZ NOT NULL DEFAULT '-infinity'
);
CREATE INDEX idx_capability_rebuild_wait_generation
    ON capability_rebuild_waits(generation_id,readiness_revision);
