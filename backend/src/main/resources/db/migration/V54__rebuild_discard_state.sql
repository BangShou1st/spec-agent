-- A prepared-but-never-activated rebuild can be explicitly abandoned so another profile can rebuild.
ALTER TABLE embedding_rebuilds DROP CONSTRAINT embedding_rebuilds_state_check;
ALTER TABLE embedding_rebuilds ADD CONSTRAINT embedding_rebuilds_state_check
    CHECK(state IN ('QUEUED','RUNNING','READY','FAILED','ACTIVE','DISCARDED'));
ALTER TABLE retrieval_index_generations DROP CONSTRAINT retrieval_index_generations_state_check;
ALTER TABLE retrieval_index_generations ADD CONSTRAINT retrieval_index_generations_state_check
    CHECK(state IN ('PREPARING','ACTIVE','RETIRED','FAILED','DISCARDED'));
