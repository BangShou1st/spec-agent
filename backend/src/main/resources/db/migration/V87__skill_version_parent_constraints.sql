-- Complete the same protocol-v9 release using a forward migration: V86 may
-- already have been applied by an isolated acceptance run; never edit its checksum.
ALTER TABLE skill_versions ADD COLUMN skill_id TEXT;
UPDATE skill_versions v SET skill_id=s.skill_id FROM skills s WHERE s.id=v.skill_row_id;
DO $$ BEGIN
    IF EXISTS(SELECT 1 FROM skill_versions WHERE skill_id IS NULL)
       OR EXISTS(SELECT 1 FROM skill_activations a JOIN skill_versions v ON v.id=a.version_id
                   WHERE a.skill_id<>v.skill_id) THEN
        RAISE EXCEPTION 'Skill version parent backfill is inconsistent';
    END IF;
    IF NOT EXISTS(SELECT 1 FROM security_mode WHERE id=1 AND protocol_version=9) THEN
        RAISE EXCEPTION 'V87 requires security protocol v9 registration';
    END IF;
END $$;
ALTER TABLE skill_versions ALTER COLUMN skill_id SET NOT NULL;
ALTER TABLE skills ADD CONSTRAINT uq_skill_row_external_id UNIQUE(id,skill_id);
ALTER TABLE skill_versions ADD CONSTRAINT skill_version_external_parent
    FOREIGN KEY(skill_row_id,skill_id) REFERENCES skills(id,skill_id);
ALTER TABLE skill_versions ADD CONSTRAINT uq_skill_version_external_id UNIQUE(id,skill_id);
ALTER TABLE skill_activations ADD CONSTRAINT skill_activation_version_parent
    FOREIGN KEY(version_id,skill_id) REFERENCES skill_versions(id,skill_id);
ALTER TABLE skills DROP CONSTRAINT skill_current_version_parent;
ALTER TABLE skills ADD CONSTRAINT skill_current_version_parent
    FOREIGN KEY(current_version_id,id) REFERENCES skill_versions(id,skill_row_id)
    ON DELETE SET NULL(current_version_id);
