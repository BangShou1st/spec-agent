-- Stop ALL instances, including API-only and credential migrators, before migration.
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM security_mode WHERE id=1 AND protocol_version=2) THEN
        RAISE EXCEPTION 'V77 requires security protocol v2 registration; refusing unknown protocol';
    END IF;
    UPDATE security_mode SET protocol_version=3,updated_at=now() WHERE id=1;
END $$;
