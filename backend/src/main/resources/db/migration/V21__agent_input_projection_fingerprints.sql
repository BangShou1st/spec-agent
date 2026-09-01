-- P1 review fix: add fingerprint column and clarify version semantics.
-- V20 shipped with projection_version = "agent-input.v2" directly coupled to
-- the wire. The durable schema should be "agent-input-projection.v1"
-- independent of "agent-input.v2" on the wire. This migration evolves the
-- table without breaking Flyway history: V20 is untouched.
ALTER TABLE agent_input_projections
    ADD COLUMN IF NOT EXISTS source_fingerprints TEXT NOT NULL DEFAULT '[]';
