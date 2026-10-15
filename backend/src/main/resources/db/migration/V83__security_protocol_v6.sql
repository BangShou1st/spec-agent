-- Scoped MCP credential envelopes are unreadable by the protocol-v5 credential reader.
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM security_mode WHERE id=1 AND protocol_version=5) THEN
        RAISE EXCEPTION 'V83 requires security protocol v5 registration; refusing unknown protocol';
    END IF;
    UPDATE security_mode SET protocol_version=6,updated_at=now() WHERE id=1;
END $$;
