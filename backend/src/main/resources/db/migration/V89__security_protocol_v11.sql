-- Old LOCAL identity adapters may resurrect revoked membership on their first request.
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM security_mode WHERE id=1 AND protocol_version=10) THEN
        RAISE EXCEPTION 'V89 requires security protocol v10 registration';
    END IF;
    UPDATE security_mode SET protocol_version=11,updated_at=now() WHERE id=1;
END $$;
