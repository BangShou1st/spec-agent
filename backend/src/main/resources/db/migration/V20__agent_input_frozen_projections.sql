-- ---------------------------------------------------------------------------
-- Durable frozen model-input projections (P1 Frozen Context Integrity).
--
-- A ContextSnapshot manifest stays the lightweight lineage authority; the
-- model-facing projection of that manifest is frozen here exactly once, the
-- first time the snapshot is projected into an AgentInputSnapshot. Every
-- later projection of the SAME snapshot replays this stored payload, so
-- retry/resume/repair can never silently rebuild model input from live
-- mutable records (node bodies, related-node bodies, route labels, capability
-- results).
--
--   snapshot_id        : one frozen projection per ContextSnapshot (unique).
--   projection_version : the contract version the payload freezes
--                        ("agent-input.v2"); unsupported versions fail closed.
--   payload            : the canonical AgentContracts serialization of the
--                        AgentInputSnapshot — byte-stable evidence of exactly
--                        what the model received as `snapshot`.
--   payload_hash       : sha256 hex of the payload; verified on every load,
--                        mismatch/malformed/foreign identity fail closed.
--
-- The payload is opaque evidence and is never updated in place; immutability
-- is enforced by "insert if absent + load-only" access, never delete/recreate.
-- ---------------------------------------------------------------------------

CREATE TABLE agent_input_projections (
    id                 UUID PRIMARY KEY,
    snapshot_id        UUID NOT NULL REFERENCES context_snapshots(id) ON DELETE CASCADE,
    projection_version VARCHAR(40) NOT NULL,
    payload            TEXT NOT NULL,
    payload_hash       VARCHAR(64) NOT NULL,
    created_at         TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Exactly-once durable freeze identity.
CREATE UNIQUE INDEX idx_agent_input_projections_snapshot
    ON agent_input_projections (snapshot_id);
