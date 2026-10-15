-- Organization/user connection lookup and persisted authentication intent require new readers.
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM security_mode WHERE id=1 AND protocol_version=4) THEN
        RAISE EXCEPTION 'V81 requires security protocol v4 registration; refusing unknown protocol';
    END IF;
    UPDATE security_mode SET protocol_version=5,updated_at=now() WHERE id=1;
END $$;
