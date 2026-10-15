ALTER TABLE workspace_rpc_grants
    ADD COLUMN source_snapshot_id UUID,
    ADD COLUMN source_payload_hash VARCHAR(64),
    ADD COLUMN source_skill_version_ids UUID[] NOT NULL DEFAULT '{}';
ALTER TABLE workspace_rpc_grants ADD CONSTRAINT workspace_rpc_source_shape CHECK(
    ((source_snapshot_id IS NULL AND source_payload_hash IS NULL AND cardinality(source_skill_version_ids)=0)
      OR (source_snapshot_id IS NOT NULL AND source_payload_hash IS NOT NULL AND source_payload_hash ~ '^[a-f0-9]{64}$'))
    AND cardinality(source_skill_version_ids)<=64
    AND array_position(source_skill_version_ids,NULL) IS NULL
);
-- Missing old bindings cannot be reconstructed from an alleged caller prompt.
UPDATE workspace_rpc_grants SET revoked=TRUE;
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM security_mode WHERE id=1 AND protocol_version=11) THEN
        RAISE EXCEPTION 'V90 requires security protocol v11 registration';
    END IF;
    UPDATE security_mode SET protocol_version=12,updated_at=now() WHERE id=1;
END $$;
