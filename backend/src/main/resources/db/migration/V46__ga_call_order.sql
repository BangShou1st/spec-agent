-- Durable ordering for repeat detection; distinct from public SSE sequence.
ALTER TABLE ga_execution_calls ADD COLUMN call_sequence BIGINT GENERATED ALWAYS AS IDENTITY;
CREATE INDEX idx_ga_calls_run_order ON ga_execution_calls(run_id, call_sequence DESC);
