-- Maintenance window only: stop ALL readers/writers before applying.
-- v1 binaries cannot safely read enc:v2 or enforce persisted Run/RPC delegation.
-- This fences future startup, not a v1 process already running during migration.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM security_mode WHERE id = 1 AND protocol_version = 1) THEN
        RAISE EXCEPTION 'V71 requires an existing security protocol v1 registration; refusing unknown protocol';
    END IF;
    UPDATE security_mode SET protocol_version = 2, updated_at = now() WHERE id = 1;
END $$;
