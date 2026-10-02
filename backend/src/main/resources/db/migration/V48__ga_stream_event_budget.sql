-- Incremental events share the same durable receipt identities and bounded sequence.
ALTER TABLE ga_execution_events DROP CONSTRAINT ga_execution_events_internal_sequence_check;
ALTER TABLE ga_execution_events ADD CONSTRAINT ga_execution_events_internal_sequence_check
    CHECK(internal_sequence BETWEEN 1 AND 8192);
