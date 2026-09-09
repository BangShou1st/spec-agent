-- Global Assistant V1 conversation persistence (Slice A).
-- Threads hold bounded working state + rolling summary (versioned, CAS).
-- Messages are user-facing only (USER|ASSISTANT). No CoT roles persisted.
-- Runs use a minimal lifecycle; cancel is a signal column, not a status.
-- Run events are persisted public events with per-run sequence.
CREATE TABLE global_assistant_threads (
    id                    UUID PRIMARY KEY,
    summary               TEXT,
    summary_version       INTEGER NOT NULL DEFAULT 0,
    working_state         JSONB,
    working_state_version INTEGER NOT NULL DEFAULT 0,
    created_at            TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at            TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE TABLE global_assistant_messages (
    id         UUID PRIMARY KEY,
    thread_id  UUID NOT NULL REFERENCES global_assistant_threads(id),
    role       VARCHAR(20) NOT NULL CHECK (role IN ('USER', 'ASSISTANT')),
    content    TEXT NOT NULL,
    run_id     UUID,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_ga_messages_thread ON global_assistant_messages(thread_id, created_at, id);
CREATE TABLE global_assistant_runs (
    id                          UUID PRIMARY KEY,
    thread_id                   UUID NOT NULL REFERENCES global_assistant_threads(id),
    status                      VARCHAR(20) NOT NULL CHECK (status IN ('CREATED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED')),
    step_count                  INTEGER NOT NULL DEFAULT 0,
    cancel_requested_at         TIMESTAMP,
    started_at                  TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at                TIMESTAMP,
    prompt_version              VARCHAR(40) NOT NULL DEFAULT 'v1',
    context_projection_version  VARCHAR(40) NOT NULL DEFAULT 'v1',
    tool_catalog_fingerprint    VARCHAR(120) NOT NULL DEFAULT 'v1',
    error_code                  VARCHAR(80)
);
CREATE INDEX idx_ga_runs_thread ON global_assistant_runs(thread_id, started_at);
-- One thread hosts at most one active run. DB is the final arbiter;
-- application locking may reduce contention but never replaces this invariant.
CREATE UNIQUE INDEX uq_ga_runs_single_active
    ON global_assistant_runs(thread_id)
    WHERE status IN ('CREATED', 'RUNNING');
CREATE TABLE global_assistant_run_events (
    id         UUID PRIMARY KEY,
    run_id     UUID NOT NULL REFERENCES global_assistant_runs(id),
    sequence   INTEGER NOT NULL,
    type       VARCHAR(60) NOT NULL,
    payload    JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE (run_id, sequence)
);
CREATE INDEX idx_ga_run_events_run ON global_assistant_run_events(run_id, sequence);
