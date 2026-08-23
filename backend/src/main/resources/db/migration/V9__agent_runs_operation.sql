-- Add operation column to agent_runs for V2 async command surface.
-- The worker uses this column to dispatch to the appropriate handler.
ALTER TABLE agent_runs ADD COLUMN operation VARCHAR(64);
