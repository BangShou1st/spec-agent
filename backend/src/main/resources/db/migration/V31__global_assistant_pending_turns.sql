-- Durable steer handoff persistence (Final Interaction Closure).
-- One unresolved pending steer per thread is enforced by partial unique index.
-- run ids stay logical UUIDs (no FK to runs) so delete ordering stays
-- thread-first and crash windows stay recoverable.
CREATE TABLE global_assistant_pending_turns (
    id UUID PRIMARY KEY,
    thread_id UUID NOT NULL REFERENCES global_assistant_threads(id),
    interrupted_run_id UUID NOT NULL,
    message TEXT NOT NULL,
    ui_context JSONB,
    status VARCHAR(20) NOT NULL CHECK (status IN ('PENDING','CLAIMED','CONSUMED','DISCARDED')),
    successor_run_id UUID NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    claimed_at TIMESTAMP NULL,
    consumed_at TIMESTAMP NULL,
    discarded_at TIMESTAMP NULL
);
CREATE UNIQUE INDEX uq_ga_pending_single_unresolved
    ON global_assistant_pending_turns(thread_id)
    WHERE status IN ('PENDING','CLAIMED');
CREATE INDEX idx_ga_pending_thread ON global_assistant_pending_turns(thread_id, status);
CREATE INDEX idx_ga_pending_interrupted ON global_assistant_pending_turns(interrupted_run_id);
