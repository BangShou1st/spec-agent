-- All execution and acceptance writers must participate in the stable scope transaction mutex.
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM security_mode WHERE id=1 AND protocol_version=6) THEN
        RAISE EXCEPTION 'V84 requires security protocol v6 registration; refusing unknown protocol';
    END IF;
    UPDATE security_mode SET protocol_version=7,updated_at=now() WHERE id=1;
END $$;
