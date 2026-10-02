-- Additive GA execution storage; legacy runs retain their engine.
ALTER TABLE global_assistant_runs ADD COLUMN engine_version VARCHAR(40) NOT NULL DEFAULT 'java-legacy.v1';
ALTER TABLE global_assistant_runs ADD CONSTRAINT uq_ga_run_thread UNIQUE (id, thread_id);
CREATE TABLE ga_executions (
    run_id UUID PRIMARY KEY,
    thread_id UUID NOT NULL,
    model_binding_id UUID NOT NULL UNIQUE,
    provider VARCHAR(40) NOT NULL CHECK (provider = 'OPENCODE_ZEN'),
    model TEXT NOT NULL,
    settings_revision TEXT NOT NULL,
    execution_epoch BIGINT NOT NULL CHECK (execution_epoch > 0),
    lease_id UUID NOT NULL,
    deadline TIMESTAMPTZ NOT NULL,
    model_calls INTEGER NOT NULL DEFAULT 0 CHECK (model_calls BETWEEN 0 AND 6),
    tool_calls INTEGER NOT NULL DEFAULT 0 CHECK (tool_calls BETWEEN 0 AND 5),
    FOREIGN KEY (run_id, thread_id) REFERENCES global_assistant_runs(id, thread_id) ON DELETE CASCADE
);
CREATE TABLE ga_execution_calls (
    run_id UUID NOT NULL REFERENCES ga_executions(run_id) ON DELETE CASCADE,
    call_id UUID NOT NULL,
    kind VARCHAR(10) NOT NULL CHECK (kind IN ('MODEL','TOOL')),
    payload_hash VARCHAR(64) NOT NULL CHECK (payload_hash ~ '^[0-9a-f]{64}$'),
    status VARCHAR(20) NOT NULL CHECK (status IN ('RESERVED','SUCCEEDED','UNKNOWN')),
    result TEXT CHECK (result IS NULL OR octet_length(result) <= 262144),
    PRIMARY KEY (run_id, call_id),
    CHECK ((status = 'SUCCEEDED') = (result IS NOT NULL))
);
CREATE TABLE ga_checkpoint_heads (
    thread_id UUID PRIMARY KEY REFERENCES global_assistant_threads(id) ON DELETE CASCADE,
    namespace VARCHAR(40) NOT NULL DEFAULT 'ga:langchain-ga.v1' CHECK (namespace = 'ga:langchain-ga.v1'),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version >= 0)
);
CREATE TABLE ga_checkpoints (
    thread_id UUID NOT NULL REFERENCES ga_checkpoint_heads(thread_id) ON DELETE CASCADE,
    checkpoint_id VARCHAR(128) NOT NULL,
    parent_checkpoint_id VARCHAR(128),
    checkpoint TEXT NOT NULL CHECK (octet_length(checkpoint) <= 1100000),
    metadata TEXT NOT NULL CHECK (octet_length(metadata) <= 1100000),
    new_versions TEXT NOT NULL CHECK (octet_length(new_versions) <= 1100000),
    PRIMARY KEY (thread_id, checkpoint_id)
);
CREATE TABLE ga_checkpoint_writes (
    thread_id UUID NOT NULL,
    checkpoint_id VARCHAR(128) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    task_path VARCHAR(1024) NOT NULL,
    write_index INTEGER NOT NULL,
    channel VARCHAR(128) NOT NULL,
    value TEXT NOT NULL CHECK (octet_length(value) <= 1100000),
    PRIMARY KEY (thread_id, checkpoint_id, task_id, write_index),
    FOREIGN KEY (thread_id, checkpoint_id) REFERENCES ga_checkpoints(thread_id, checkpoint_id) ON DELETE CASCADE
);
