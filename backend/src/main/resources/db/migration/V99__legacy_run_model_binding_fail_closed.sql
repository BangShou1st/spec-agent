-- A pre-V98 active Run may have a process-local binding that cannot be recovered.
-- Lock it to a non-matching sentinel so a restart cannot silently bind it anew.
-- CREATED rows have not reached inference yet and may safely bind on first use.
UPDATE agent_runs
   SET model_binding_fingerprint = 'legacy-unbound'
 WHERE status NOT IN ('created', 'completed', 'failed');
