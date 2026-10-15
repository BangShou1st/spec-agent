ALTER TABLE skills ADD COLUMN org_id UUID REFERENCES organizations(id);
ALTER TABLE skill_staged_imports ADD COLUMN org_id UUID REFERENCES organizations(id);
ALTER TABLE skill_activations ADD COLUMN org_id UUID REFERENCES organizations(id);
UPDATE skills SET org_id='00000000-0000-4000-8000-000000000001';
UPDATE skill_staged_imports SET org_id='00000000-0000-4000-8000-000000000001';
UPDATE skill_activations a SET org_id=p.org_id FROM projects p WHERE p.id=a.project_id;
DO $$ BEGIN
    IF EXISTS(SELECT 1 FROM skills WHERE org_id IS NULL)
       OR EXISTS(SELECT 1 FROM skill_staged_imports WHERE org_id IS NULL)
       OR EXISTS(SELECT 1 FROM skill_activations WHERE org_id IS NULL)
       OR EXISTS(SELECT 1 FROM skill_activations a JOIN skill_versions v ON v.id=a.version_id
                   JOIN skills s ON s.id=v.skill_row_id WHERE s.org_id<>a.org_id)
       OR EXISTS(SELECT 1 FROM skills s JOIN skill_versions v ON v.id=s.current_version_id
                   WHERE v.skill_row_id<>s.id) THEN
        RAISE EXCEPTION 'Skill ownership/parent backfill is inconsistent; manual reconciliation required';
    END IF;
END $$;
ALTER TABLE skills ALTER COLUMN org_id SET NOT NULL;
ALTER TABLE skill_staged_imports ALTER COLUMN org_id SET NOT NULL;
ALTER TABLE skill_activations ALTER COLUMN org_id SET NOT NULL;
ALTER TABLE skills ADD CONSTRAINT uq_skill_id_org UNIQUE(id,org_id);
ALTER TABLE skills ADD CONSTRAINT uq_skill_external_id_org UNIQUE(skill_id,org_id);
ALTER TABLE skill_versions ADD CONSTRAINT uq_skill_version_parent UNIQUE(id,skill_row_id);
ALTER TABLE skills ADD CONSTRAINT skill_current_version_parent
    FOREIGN KEY(current_version_id,id) REFERENCES skill_versions(id,skill_row_id)
    ON DELETE SET NULL(current_version_id);
ALTER TABLE skill_activations ADD CONSTRAINT skill_activation_project_org
    FOREIGN KEY(project_id,org_id) REFERENCES projects(id,org_id);
ALTER TABLE skill_activations ADD CONSTRAINT skill_activation_skill_org
    FOREIGN KEY(skill_id,org_id) REFERENCES skills(skill_id,org_id);
DROP INDEX uq_skills_enabled_name;
CREATE UNIQUE INDEX uq_skills_enabled_name ON skills(org_id,name) WHERE enabled=true;
ALTER TABLE skill_versions DROP CONSTRAINT skill_versions_content_hash_key;
ALTER TABLE skill_versions ADD CONSTRAINT uq_skill_version_content UNIQUE(skill_row_id,content_hash);

-- Old binaries read the whole catalog and omit org on writes. Require the new reader.
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM security_mode WHERE id=1 AND protocol_version=8) THEN
        RAISE EXCEPTION 'V86 requires security protocol v8 registration';
    END IF;
    UPDATE security_mode SET protocol_version=9,updated_at=now() WHERE id=1;
END $$;
