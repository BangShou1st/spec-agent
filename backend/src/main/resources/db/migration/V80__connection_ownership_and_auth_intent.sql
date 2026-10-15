ALTER TABLE connections
    ADD COLUMN org_id UUID REFERENCES organizations(id),
    ADD COLUMN owner_kind VARCHAR(8) NOT NULL DEFAULT 'ORG' CHECK(owner_kind IN ('ORG','USER')),
    ADD COLUMN owner_user_id UUID REFERENCES app_users(id),
    ADD COLUMN auth_kind VARCHAR(8) NOT NULL DEFAULT 'NONE' CHECK(auth_kind IN ('NONE','BEARER')),
    ADD COLUMN configuration_version BIGINT NOT NULL DEFAULT 1 CHECK(configuration_version>0);
ALTER TABLE connection_credentials ADD COLUMN org_id UUID REFERENCES organizations(id);
UPDATE connections SET org_id='00000000-0000-4000-8000-000000000001',
    auth_kind=CASE WHEN credential_ref IS NULL THEN 'NONE' ELSE 'BEARER' END;
UPDATE connection_credentials k SET org_id=c.org_id FROM connections c WHERE c.id=k.connection_id;
DO $$ BEGIN
    IF EXISTS(SELECT 1 FROM connections WHERE org_id IS NULL)
        OR EXISTS(SELECT 1 FROM connection_credentials WHERE org_id IS NULL) THEN
        RAISE EXCEPTION 'Connection ownership backfill failed';
    END IF;
END $$;
ALTER TABLE connections ALTER COLUMN org_id SET NOT NULL;
ALTER TABLE connection_credentials ALTER COLUMN org_id SET NOT NULL;
ALTER TABLE connections ADD CONSTRAINT connection_owner_shape CHECK(
    (owner_kind='ORG' AND owner_user_id IS NULL) OR (owner_kind='USER' AND owner_user_id IS NOT NULL)
);
ALTER TABLE connections ADD CONSTRAINT uq_connection_id_org UNIQUE(id,org_id);
ALTER TABLE connection_credentials ADD CONSTRAINT credential_connection_same_org
    FOREIGN KEY(connection_id,org_id) REFERENCES connections(id,org_id);
ALTER TABLE connection_credentials ADD CONSTRAINT uq_credential_connection_org UNIQUE(ref,connection_id,org_id);
-- Keep the authentication intent even when a broken/missing reference is removed.
UPDATE connections c SET credential_ref=NULL,status='FAILED',enabled=FALSE,last_error='MCP_CREDENTIAL_UNAVAILABLE'
WHERE credential_ref IS NOT NULL AND NOT EXISTS(
    SELECT 1 FROM connection_credentials k WHERE k.ref=c.credential_ref AND k.connection_id=c.id AND k.org_id=c.org_id
);
ALTER TABLE connections ADD CONSTRAINT selected_credential_same_connection
    FOREIGN KEY(credential_ref,id,org_id) REFERENCES connection_credentials(ref,connection_id,org_id)
    ON DELETE SET NULL(credential_ref);
DROP INDEX uq_connections_enabled_name;
CREATE UNIQUE INDEX uq_connections_enabled_name ON connections(org_id,owner_kind,COALESCE(owner_user_id,'00000000-0000-0000-0000-000000000000'::uuid),name) WHERE enabled;
