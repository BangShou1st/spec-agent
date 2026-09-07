-- Slice 0: autonomous continuation chain linkage on agent_runs.
--
-- An AgentRun stays one durable Observe -> Decide -> Act cycle. A continuation
-- chain links several runs: parent_run_id points at the run whose terminal
-- boundary spawned this one, root_run_id names the chain head for querying,
-- and cycle_index counts the child depth (root runs use 0).
--
-- All columns stay NULL for pre-continuation rows (lazy chain identity: a
-- null root/cycle reads as "this run is its own root at cycle 0"). No
-- backfill. No loop entity, no status machine, no semantic flags.
ALTER TABLE agent_runs
    ADD COLUMN IF NOT EXISTS parent_run_id UUID REFERENCES agent_runs(id),
    ADD COLUMN IF NOT EXISTS root_run_id UUID REFERENCES agent_runs(id),
    ADD COLUMN IF NOT EXISTS cycle_index INT;
ALTER TABLE agent_runs
    ADD CONSTRAINT chk_agent_runs_cycle_index
    CHECK (cycle_index IS NULL OR cycle_index >= 0);
CREATE INDEX IF NOT EXISTS idx_agent_runs_parent_run_id
    ON agent_runs (parent_run_id);
CREATE INDEX IF NOT EXISTS idx_agent_runs_root_run_id
    ON agent_runs (root_run_id);
