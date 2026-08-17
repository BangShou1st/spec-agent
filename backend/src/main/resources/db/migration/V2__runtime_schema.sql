-- Phase 2: Runtime Kernel and Persistence Model
-- Core tables for deterministic requirement-clarification runtime.
-- No model integration, no business-domain columns.

-- ---------------------------------------------------------------------------
-- profiles
-- Generic requirement dimensions / output preferences. Configuration, not code.
-- ---------------------------------------------------------------------------
CREATE TABLE profiles (
    id uuid PRIMARY KEY,
    name VARCHAR(120) NOT NULL UNIQUE,
    description TEXT,
    aspects jsonb,
    spec_section_definitions jsonb,
    question_policy_hints jsonb,
    tone VARCHAR(60),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO profiles (id, name, description, aspects, spec_section_definitions, question_policy_hints, tone)
VALUES (
    CAST('11111111-1111-1111-1111-111111111111' AS uuid),
    'generic_requirement',
    'Default generic requirement clarification profile. Domain-agnostic.',
    CAST('[]' AS jsonb),
    CAST('[]' AS jsonb),
    CAST('[]' AS jsonb),
    'neutral'
);

-- ---------------------------------------------------------------------------
-- projects
-- Requirement exploration workspace. active_route_id is the current focus.
-- ---------------------------------------------------------------------------
CREATE TABLE projects (
    id uuid PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    active_route_id uuid,
    default_profile_id uuid REFERENCES profiles(id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ---------------------------------------------------------------------------
-- routes
-- Explicit exploration route. lifecycle_status is open|superseded|archived|deleted.
-- 'active' is NOT a lifecycle status; it is represented by project.active_route_id.
-- ---------------------------------------------------------------------------
CREATE TABLE routes (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id),
    root_node_id uuid,
    tip_node_id uuid,
    lifecycle_status VARCHAR(20) NOT NULL,
    label VARCHAR(255),
    created_from_node_id uuid,
    supersedes_route_id uuid REFERENCES routes(id),
    replacement_of_node_id uuid,
    created_by_run_id uuid,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_routes_project ON routes(project_id);

-- ---------------------------------------------------------------------------
-- nodes
-- Immutable clarification prompt in the exploration tree.
-- question/purpose/options are fixed at creation and never edited.
-- ---------------------------------------------------------------------------
CREATE TABLE nodes (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id),
    parent_node_id uuid REFERENCES nodes(id),
    created_by_run_id uuid,
    supersedes_node_id uuid REFERENCES nodes(id),
    question TEXT NOT NULL,
    purpose TEXT,
    options jsonb,
    allow_free_answer BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_nodes_project ON nodes(project_id);
CREATE INDEX idx_nodes_parent ON nodes(parent_node_id);

-- ---------------------------------------------------------------------------
-- answers
-- Immutable user answer to a node. One finalization per (route_id, node_id).
-- ---------------------------------------------------------------------------
CREATE TABLE answers (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id),
    route_id uuid NOT NULL REFERENCES routes(id),
    node_id uuid NOT NULL REFERENCES nodes(id),
    selected_option_id VARCHAR(120),
    free_text TEXT,
    created_by_user VARCHAR(120),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (route_id, node_id)
);
CREATE INDEX idx_answers_route ON answers(route_id);
CREATE INDEX idx_answers_node ON answers(node_id);

-- ---------------------------------------------------------------------------
-- answer_patches
-- Structured requirement-state changes derived from one answer.
-- Replaying these along the active route lineage derives RequirementState.
-- ---------------------------------------------------------------------------
CREATE TABLE answer_patches (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id),
    route_id uuid NOT NULL REFERENCES routes(id),
    source_node_id uuid NOT NULL REFERENCES nodes(id),
    source_answer_id uuid NOT NULL REFERENCES answers(id),
    claims jsonb,
    created_by_run_id uuid,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_patches_route ON answer_patches(route_id);
CREATE INDEX idx_patches_answer ON answer_patches(source_answer_id);

-- ---------------------------------------------------------------------------
-- agent_runs
-- One controlled agent execution. Does not own persistent state itself.
-- ---------------------------------------------------------------------------
CREATE TABLE agent_runs (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id),
    route_id uuid REFERENCES routes(id),
    trigger_type VARCHAR(40) NOT NULL,
    input_node_id uuid REFERENCES nodes(id),
    context_snapshot_id uuid,
    produced_node_id uuid REFERENCES nodes(id),
    produced_answer_id uuid REFERENCES answers(id),
    produced_patch_id uuid REFERENCES answer_patches(id),
    produced_spec_snapshot_id uuid,
    status VARCHAR(30) NOT NULL,
    trace jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);
CREATE INDEX idx_agent_runs_project ON agent_runs(project_id);

-- ---------------------------------------------------------------------------
-- context_snapshots
-- Exact lineage context used for one agent run. Derived, not source of truth.
-- ---------------------------------------------------------------------------
CREATE TABLE context_snapshots (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id),
    route_id uuid NOT NULL REFERENCES routes(id),
    tip_node_id uuid REFERENCES nodes(id),
    operation_type VARCHAR(30) NOT NULL,
    included_node_ids jsonb,
    included_answer_ids jsonb,
    included_patch_ids jsonb,
    excluded_route_ids jsonb,
    special_inputs jsonb,
    context_hash VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_context_snapshots_project ON context_snapshots(project_id);

-- ---------------------------------------------------------------------------
-- spec_snapshots
-- Generated spec for one route tip. Derived artifact, not source of truth.
-- Confirmed claims must carry source references.
-- ---------------------------------------------------------------------------
CREATE TABLE spec_snapshots (
    id uuid PRIMARY KEY,
    project_id uuid NOT NULL REFERENCES projects(id),
    route_id uuid NOT NULL REFERENCES routes(id),
    tip_node_id uuid REFERENCES nodes(id),
    context_snapshot_id uuid REFERENCES context_snapshots(id),
    format VARCHAR(30),
    sections jsonb,
    unresolved_items jsonb,
    source_refs jsonb,
    created_by_run_id uuid,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_spec_snapshots_route ON spec_snapshots(route_id);
