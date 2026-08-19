-- Phase 7.3 correction: durable branch provenance and frozen inherited answer refs.
-- Inherited rows reference immutable answers; payloads are never cloned.

ALTER TABLE routes
    ADD COLUMN branch_type VARCHAR(20),
    ADD COLUMN source_route_id uuid REFERENCES routes(id),
    ADD COLUMN branch_at_node_id uuid REFERENCES nodes(id);

CREATE INDEX idx_routes_source_route ON routes(source_route_id);
CREATE INDEX idx_routes_branch_at_node ON routes(branch_at_node_id);

CREATE TABLE route_inherited_answers (
    branch_route_id uuid NOT NULL REFERENCES routes(id),
    ordinal INTEGER NOT NULL,
    node_id uuid NOT NULL REFERENCES nodes(id),
    answer_id uuid NOT NULL REFERENCES answers(id),
    owner_route_id uuid NOT NULL REFERENCES routes(id),
    PRIMARY KEY (branch_route_id, ordinal),
    UNIQUE (branch_route_id, node_id),
    UNIQUE (branch_route_id, answer_id)
);

CREATE INDEX idx_route_inherited_answers_answer ON route_inherited_answers(answer_id);
