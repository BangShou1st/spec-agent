ALTER TABLE workspace_rpc_grants ADD COLUMN source_mcp_bindings JSONB NOT NULL DEFAULT '[]';
ALTER TABLE workspace_rpc_grants ADD CONSTRAINT workspace_rpc_mcp_source_shape
    CHECK(jsonb_typeof(source_mcp_bindings)='array' AND jsonb_array_length(source_mcp_bindings)<=64);
UPDATE workspace_rpc_grants SET revoked=TRUE;
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM security_mode WHERE id=1 AND protocol_version=12) THEN
        RAISE EXCEPTION 'V91 requires security protocol v12 registration';
    END IF;
    UPDATE security_mode SET protocol_version=13,updated_at=now() WHERE id=1;
END $$;
