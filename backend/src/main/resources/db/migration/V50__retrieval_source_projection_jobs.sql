-- Raw authorized projection inputs, not a second retrieval/vector index.
CREATE TABLE retrieval_source_projections (
    node_id UUID PRIMARY KEY REFERENCES nodes(id) ON DELETE CASCADE,
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    source_version UUID NOT NULL,
    raw_text TEXT NOT NULL,
    raw_hash VARCHAR(64) NOT NULL,
    metadata JSONB NOT NULL,
    state VARCHAR(12) NOT NULL CHECK(state IN ('PENDING','READY')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_retrieval_source_pending ON retrieval_source_projections(project_id) WHERE state='PENDING';
