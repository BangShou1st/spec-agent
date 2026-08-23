-- Stage C: Graph Workspace V2 — generic workspace-unit nodes, semantic
-- relations, and the typed graph operation log backing Undo/Redo.
--
-- Additive only: existing rows are interpreted as INTERACTION/QUESTION nodes
-- authored by the agent. The obsolete `question NOT NULL` constraint is
-- relaxed separately in V11 after compatibility tests.

-- Generic workspace-unit classification. Kind is a stable outer category;
-- subtype is an open, per-kind vocabulary validated by the Runtime.
ALTER TABLE nodes
    ADD COLUMN kind            VARCHAR(30)  NOT NULL DEFAULT 'INTERACTION',
    ADD COLUMN subtype         VARCHAR(60)  NOT NULL DEFAULT 'QUESTION',
    ADD COLUMN content         JSONB,
    ADD COLUMN author_kind     VARCHAR(20)  NOT NULL DEFAULT 'AGENT',
    ADD COLUMN knowledge_status VARCHAR(30),
    ADD COLUMN retracted_at    TIMESTAMP;

-- Draft/knowledge editing support (user-authored draft content only).
ALTER TABLE nodes
    ADD COLUMN updated_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP;

CREATE INDEX idx_nodes_project_kind ON nodes (project_id, kind);

-- ---------------------------------------------------------------------------
-- node_relations
-- Semantic relations between nodes. Stored separately from visible
-- continuation lineage (nodes.parent_node_id); never rendered as default
-- Canvas edges. Model-inferred relations enter as Advisor proposals and are
-- persisted only after acceptance (created_by_proposal_id records this).
-- ---------------------------------------------------------------------------
CREATE TABLE node_relations (
    id                       UUID PRIMARY KEY,
    project_id               UUID NOT NULL REFERENCES projects(id),
    source_node_id           UUID NOT NULL REFERENCES nodes(id),
    target_node_id           UUID NOT NULL REFERENCES nodes(id),
    relation_type            VARCHAR(40) NOT NULL,
    origin                   VARCHAR(20) NOT NULL,
    status                   VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_by_proposal_id   UUID,
    created_by_run_id        UUID,
    created_at               TIMESTAMP NOT NULL DEFAULT NOW(),
    retracted_at             TIMESTAMP,
    CHECK (source_node_id <> target_node_id)
);

-- One active relation per (source, target, type); retracted rows may repeat.
CREATE UNIQUE INDEX idx_node_relations_active_unique
    ON node_relations (source_node_id, target_node_id, relation_type)
    WHERE status = 'ACTIVE';

CREATE INDEX idx_node_relations_project ON node_relations (project_id, status);
CREATE INDEX idx_node_relations_source ON node_relations (source_node_id);
CREATE INDEX idx_node_relations_target ON node_relations (target_node_id);

-- ---------------------------------------------------------------------------
-- graph_operations
-- Typed, append-preserving log of user-visible durable graph mutations.
-- Undo/Redo is operation-specific compensation over this log; immutable
-- answers and historical lineage are never physically deleted.
-- ---------------------------------------------------------------------------
CREATE TABLE graph_operations (
    id            UUID PRIMARY KEY,
    project_id    UUID NOT NULL REFERENCES projects(id),
    actor         VARCHAR(20) NOT NULL,
    type          VARCHAR(60) NOT NULL,
    targets       JSONB NOT NULL DEFAULT '[]',
    before_refs   JSONB NOT NULL DEFAULT '[]',
    after_refs    JSONB NOT NULL DEFAULT '[]',
    caused_by     VARCHAR(128),
    reversible    BOOLEAN NOT NULL DEFAULT TRUE,
    status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMP NOT NULL DEFAULT NOW(),
    undone_at     TIMESTAMP,
    CHECK (actor IN ('USER', 'AGENT', 'SYSTEM')),
    CHECK (status IN ('ACTIVE', 'UNDONE'))
);

CREATE INDEX idx_graph_operations_project ON graph_operations (project_id, status, created_at);
