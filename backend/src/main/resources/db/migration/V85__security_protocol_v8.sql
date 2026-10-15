-- SSE readers must reauthorize every replayed event and never skip committed cross-instance events.
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM security_mode WHERE id=1 AND protocol_version=7) THEN
        RAISE EXCEPTION 'V85 requires security protocol v7 registration; refusing unknown protocol';
    END IF;
    UPDATE security_mode SET protocol_version=8,updated_at=now() WHERE id=1;
END $$;
