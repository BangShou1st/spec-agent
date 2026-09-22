-- Standard Agent Memory + RAG V1.
-- Retrieval is a rebuildable projection; canonical graph, route, answer,
-- patch, and resource rows remain the source of truth.

CREATE EXTENSION IF NOT EXISTS pg_trgm;
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE retrieval_entries (
    id                    UUID PRIMARY KEY,
    project_id            UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    route_id              UUID REFERENCES routes(id),
    source_kind           VARCHAR(40) NOT NULL,
    source_id             UUID NOT NULL,
    source_ref            VARCHAR(240) NOT NULL,
    scope                 VARCHAR(20) NOT NULL,
    authority             VARCHAR(30) NOT NULL,
    content               TEXT NOT NULL,
    content_hash          VARCHAR(64) NOT NULL,
    metadata              JSONB NOT NULL DEFAULT '{}'::jsonb,
    search_vector         TSVECTOR GENERATED ALWAYS AS (
                              to_tsvector('simple', coalesce(content, ''))
                          ) STORED,
    embedding             VECTOR,
    embedding_model       VARCHAR(160),
    embedding_dimensions  INTEGER,
    embedding_status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    retracted_at          TIMESTAMP,
    CONSTRAINT uq_retrieval_entries_source UNIQUE (project_id, source_ref),
    CONSTRAINT ck_retrieval_entries_scope CHECK (scope IN ('ROUTE', 'PROJECT', 'RESOURCE')),
    CONSTRAINT ck_retrieval_entries_embedding_status CHECK (
        embedding_status IN ('PENDING', 'READY', 'FAILED', 'UNAVAILABLE')
    )
);

CREATE INDEX idx_retrieval_entries_project_scope
    ON retrieval_entries (project_id, scope, retracted_at);
CREATE INDEX idx_retrieval_entries_route
    ON retrieval_entries (project_id, route_id, retracted_at);
CREATE INDEX idx_retrieval_entries_source_kind
    ON retrieval_entries (project_id, source_kind, retracted_at);
CREATE INDEX idx_retrieval_entries_search_vector
    ON retrieval_entries USING GIN (search_vector);
CREATE INDEX idx_retrieval_entries_content_trgm
    ON retrieval_entries USING GIN (content gin_trgm_ops);

COMMENT ON TABLE retrieval_entries IS
    'Rebuildable lexical/trigram/vector retrieval projection; never canonical truth';
COMMENT ON COLUMN retrieval_entries.embedding IS
    'Optional pgvector embedding. NULL is valid and keeps lexical/graph retrieval available.';
