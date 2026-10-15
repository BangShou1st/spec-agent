ALTER TABLE embedding_service_settings ADD COLUMN org_id UUID REFERENCES organizations(id);
ALTER TABLE embedding_service_revisions ADD COLUMN org_id UUID REFERENCES organizations(id);
ALTER TABLE embedding_probes ADD COLUMN org_id UUID REFERENCES organizations(id);
ALTER TABLE embedding_rebuilds ADD COLUMN org_id UUID REFERENCES organizations(id);
UPDATE embedding_service_settings SET org_id='00000000-0000-4000-8000-000000000001';
UPDATE embedding_service_revisions SET org_id='00000000-0000-4000-8000-000000000001';
UPDATE embedding_probes SET org_id='00000000-0000-4000-8000-000000000001';
UPDATE embedding_rebuilds SET org_id='00000000-0000-4000-8000-000000000001';
DO $$ BEGIN
    IF EXISTS(SELECT 1 FROM embedding_service_settings WHERE org_id IS NULL)
        OR EXISTS(SELECT 1 FROM embedding_service_revisions WHERE org_id IS NULL)
        OR EXISTS(SELECT 1 FROM embedding_probes WHERE org_id IS NULL)
        OR EXISTS(SELECT 1 FROM embedding_rebuilds WHERE org_id IS NULL) THEN
        RAISE EXCEPTION 'Embedding organization backfill failed';
    END IF;
END $$;
ALTER TABLE embedding_service_settings ALTER COLUMN org_id SET NOT NULL;
ALTER TABLE embedding_service_revisions ALTER COLUMN org_id SET NOT NULL;
ALTER TABLE embedding_probes ALTER COLUMN org_id SET NOT NULL;
ALTER TABLE embedding_rebuilds ALTER COLUMN org_id SET NOT NULL;
ALTER TABLE embedding_service_settings DROP CONSTRAINT embedding_service_settings_pkey;
ALTER TABLE embedding_service_settings ADD PRIMARY KEY(org_id,singleton_id);
ALTER TABLE embedding_service_revisions DROP CONSTRAINT embedding_service_revisions_pkey;
ALTER TABLE embedding_service_revisions ADD PRIMARY KEY(org_id,revision);

-- Vector semantics keep their global content hash. Approval/transport/credentials belong to an organization.
CREATE TABLE embedding_profile_registrations (
    org_id UUID NOT NULL REFERENCES organizations(id),
    profile_id VARCHAR(64) NOT NULL REFERENCES embedding_profiles(profile_id) ON DELETE CASCADE,
    config JSONB NOT NULL,
    service_revision BIGINT NOT NULL,
    PRIMARY KEY(org_id,profile_id),
    FOREIGN KEY(org_id,service_revision) REFERENCES embedding_service_revisions(org_id,revision) ON DELETE CASCADE
);
INSERT INTO embedding_profile_registrations(org_id,profile_id,config,service_revision)
SELECT '00000000-0000-4000-8000-000000000001'::uuid,profile_id,config,service_revision FROM embedding_profiles;
ALTER TABLE embedding_rebuilds ADD CONSTRAINT embedding_rebuild_profile_same_org
    FOREIGN KEY(org_id,profile_id) REFERENCES embedding_profile_registrations(org_id,profile_id);
ALTER TABLE embedding_service_settings ADD CONSTRAINT embedding_candidate_profile_same_org
    FOREIGN KEY(org_id,candidate_profile) REFERENCES embedding_profile_registrations(org_id,profile_id);
