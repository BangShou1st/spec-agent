-- ---------------------------------------------------------------------------
-- Node-query bounded 1-hop semantic context
-- A "ask AI about this node" (NODE_QUERY) context now carries the ACTIVE
-- semantic relations touching the anchor plus the canonical node ids at the
-- other end. Both are bounded (1 hop, ACTIVE only, no recursion) so the
-- context never scans the whole workspace. The lineage (included_node_ids)
-- stays untouched — related nodes are NOT added to it (B7.1).
--
--   related_node_ids : jsonb array of canonical UUIDs at the other end of the
--                      anchor's ACTIVE relations (never in the lineage).
--   relations_json    : jsonb array of {sourceNodeId, targetNodeId, relationType}
--                      objects, direction preserved exactly as stored.
-- ---------------------------------------------------------------------------
ALTER TABLE context_snapshots
    ADD COLUMN related_node_ids jsonb,
    ADD COLUMN relations_json jsonb;

-- Backfill existing rows (no semantic context was captured before this version).
UPDATE context_snapshots SET related_node_ids = '[]'::jsonb, relations_json = '[]'::jsonb
WHERE related_node_ids IS NULL OR relations_json IS NULL;
