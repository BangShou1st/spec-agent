-- Retrieval is derived state and must never prevent canonical Route cleanup in
-- maintenance/evaluation flows. Keep route provenance while the Route exists,
-- but cascade derived rows when a Route is physically removed.
ALTER TABLE retrieval_entries
    DROP CONSTRAINT IF EXISTS retrieval_entries_route_id_fkey;

ALTER TABLE retrieval_entries
    ADD CONSTRAINT retrieval_entries_route_id_fkey
    FOREIGN KEY (route_id) REFERENCES routes(id) ON DELETE CASCADE;
