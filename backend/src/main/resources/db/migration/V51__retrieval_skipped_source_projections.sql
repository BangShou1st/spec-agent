-- Retain only an exclusion marker for non-indexable resources, avoiding repeated backfill.
ALTER TABLE retrieval_source_projections DROP CONSTRAINT retrieval_source_projections_state_check;
ALTER TABLE retrieval_source_projections ADD CONSTRAINT retrieval_source_projections_state_check CHECK(state IN ('PENDING','READY','SKIPPED'));
