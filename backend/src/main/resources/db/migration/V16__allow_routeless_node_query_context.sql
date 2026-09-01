-- ---------------------------------------------------------------------------
-- Routeless NODE_QUERY context
-- A floating persisted canonical Node (routeIds=[]) can be the anchor of an
-- "Ask AI" NODE_QUERY without any Route: the snapshot is the anchor node's
-- own lineage and carries route_id = NULL. Only this column is relaxed; every
-- other context_snapshots constraint stays unchanged.
-- ---------------------------------------------------------------------------
ALTER TABLE context_snapshots
    ALTER COLUMN route_id DROP NOT NULL;