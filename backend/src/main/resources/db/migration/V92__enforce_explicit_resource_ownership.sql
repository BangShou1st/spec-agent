-- Final ownership tightening after V61 backfill and migration of all application writers.
-- Never repair a missing owner by assigning it to the local/legacy organization.
SET LOCAL lock_timeout = '5s';

DO $$
DECLARE missing bigint;
BEGIN
    SELECT count(*) INTO missing FROM projects WHERE org_id IS NULL OR created_by IS NULL;
    IF missing <> 0 THEN RAISE EXCEPTION 'V92: projects have % rows without explicit organization/creator', missing; END IF;
    SELECT count(*) INTO missing FROM profiles WHERE org_id IS NULL;
    IF missing <> 0 THEN RAISE EXCEPTION 'V92: profiles have % rows without explicit organization', missing; END IF;
    SELECT count(*) INTO missing FROM global_assistant_threads WHERE org_id IS NULL OR owner_user_id IS NULL;
    IF missing <> 0 THEN RAISE EXCEPTION 'V92: threads have % rows without explicit organization/owner', missing; END IF;
    SELECT count(*) INTO missing FROM project_members WHERE org_id IS NULL;
    IF missing <> 0 THEN RAISE EXCEPTION 'V92: project memberships have % rows without explicit organization', missing; END IF;
END $$;

ALTER TABLE projects ALTER COLUMN org_id SET NOT NULL;
ALTER TABLE projects ALTER COLUMN created_by SET NOT NULL;
ALTER TABLE profiles ALTER COLUMN org_id SET NOT NULL;
ALTER TABLE global_assistant_threads ALTER COLUMN org_id SET NOT NULL;
ALTER TABLE global_assistant_threads ALTER COLUMN owner_user_id SET NOT NULL;
ALTER TABLE project_members ALTER COLUMN org_id SET NOT NULL;

ALTER TABLE projects ADD CONSTRAINT fk_projects_organization FOREIGN KEY (org_id) REFERENCES organizations(id);
ALTER TABLE projects ADD CONSTRAINT fk_projects_creator FOREIGN KEY (created_by) REFERENCES app_users(id);
ALTER TABLE profiles ADD CONSTRAINT fk_profiles_organization FOREIGN KEY (org_id) REFERENCES organizations(id) ON DELETE CASCADE;
ALTER TABLE global_assistant_threads ADD CONSTRAINT fk_ga_threads_organization FOREIGN KEY (org_id) REFERENCES organizations(id);
ALTER TABLE global_assistant_threads ADD CONSTRAINT fk_ga_threads_owner FOREIGN KEY (owner_user_id) REFERENCES app_users(id);

ALTER TABLE model_providers ALTER COLUMN org_id DROP DEFAULT;
ALTER TABLE model_provider_settings ALTER COLUMN org_id DROP DEFAULT;
ALTER TABLE opencode_settings ALTER COLUMN org_id DROP DEFAULT;
ALTER TABLE openrouter_settings ALTER COLUMN org_id DROP DEFAULT;
ALTER TABLE custom_provider_settings ALTER COLUMN org_id DROP DEFAULT;
