-- V2 Stage A: append-only agent run events.
-- Sanitized trace/progress records per run phase. Never prompt text,
-- provider payloads, or hidden reasoning.

CREATE TABLE agent_run_events (
    id uuid PRIMARY KEY,
    run_id uuid NOT NULL REFERENCES agent_runs(id),
    sequence INT NOT NULL,
    phase VARCHAR(40) NOT NULL,
    event_type VARCHAR(60) NOT NULL,
    payload jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (run_id, sequence)
);
CREATE INDEX idx_agent_run_events_run ON agent_run_events(run_id);
