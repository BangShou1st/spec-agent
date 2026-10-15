-- Rebuild ownership/grant fencing is incompatible with the old unleased poller/recovery.
-- Maintenance window: stop ALL old instances before applying, including API-only schedulers.
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM security_mode WHERE id=1 AND protocol_version=3) THEN
        RAISE EXCEPTION 'V79 requires security protocol v3 registration; refusing unknown protocol';
    END IF;
    UPDATE security_mode SET protocol_version=4,updated_at=now() WHERE id=1;
END $$;
