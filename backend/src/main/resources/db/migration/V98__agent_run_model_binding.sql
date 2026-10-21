-- Durable, non-secret fingerprint of the first model configuration used by a Run.
-- The fingerprint is a SHA-256 digest; provider credentials are never stored here.
ALTER TABLE agent_runs
    ADD COLUMN model_binding_fingerprint CHAR(64) NULL;
