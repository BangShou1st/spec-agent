ALTER TABLE ga_executions ADD COLUMN execution_request TEXT;
ALTER TABLE ga_executions ADD COLUMN execution_request_hash CHAR(64);
ALTER TABLE ga_executions ADD COLUMN executor_claimed BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE ga_checkpoint_heads ADD COLUMN completed_checkpoint_id VARCHAR(128);
ALTER TABLE ga_checkpoint_heads ADD COLUMN public_history_boundary UUID;
ALTER TABLE ga_executions ADD CONSTRAINT ga_execution_request_size
    CHECK (execution_request IS NULL OR octet_length(execution_request)<=262144);
CREATE TABLE ga_execution_events (
    run_id UUID NOT NULL REFERENCES ga_executions(run_id) ON DELETE CASCADE,
    event_id UUID NOT NULL,
    internal_sequence INTEGER NOT NULL CHECK(internal_sequence BETWEEN 1 AND 256),
    payload_hash CHAR(64) NOT NULL,
    PRIMARY KEY(run_id,event_id),
    UNIQUE(run_id,internal_sequence)
);
