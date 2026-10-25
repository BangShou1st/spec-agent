ALTER TABLE agent_runs ADD COLUMN model_connection_reference VARCHAR(128);
CREATE FUNCTION protect_run_model_connection_reference() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.model_connection_reference IS NOT NULL
       AND NEW.model_connection_reference IS DISTINCT FROM OLD.model_connection_reference THEN
        RAISE EXCEPTION 'RUN_MODEL_CONNECTION_IMMUTABLE' USING ERRCODE='23514';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER protect_run_model_connection_reference
BEFORE UPDATE OF model_connection_reference ON agent_runs
FOR EACH ROW EXECUTE FUNCTION protect_run_model_connection_reference();
