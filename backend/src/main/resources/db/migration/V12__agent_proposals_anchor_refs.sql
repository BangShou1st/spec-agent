-- Persist the proposal's declared anchor refs so acceptance-time staleness
-- validation can re-check them against current graph facts (e.g. the anchor
-- must still be the route tip for node-creating proposals).
ALTER TABLE agent_proposals
    ADD COLUMN anchor_refs JSONB NOT NULL DEFAULT '[]';
