-- Forward-only protection. Never guess a lost V99 digest from current settings.
-- Existing legacy-unbound rows remain fail-closed; no status/data backfill here.
-- Same-value verification and the first NULL -> fingerprint binding remain legal.
CREATE OR REPLACE FUNCTION protect_agent_run_model_binding()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    IF OLD.model_binding_fingerprint IS NOT NULL
       AND NEW.model_binding_fingerprint IS DISTINCT FROM OLD.model_binding_fingerprint THEN
        RAISE EXCEPTION 'RUN_MODEL_BINDING_IMMUTABLE' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS protect_agent_run_model_binding ON agent_runs;
CREATE TRIGGER protect_agent_run_model_binding
BEFORE UPDATE OF model_binding_fingerprint ON agent_runs
FOR EACH ROW EXECUTE FUNCTION protect_agent_run_model_binding();
