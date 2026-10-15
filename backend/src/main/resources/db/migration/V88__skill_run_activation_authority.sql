ALTER TABLE agent_runs ADD CONSTRAINT uq_skill_execution_run_project UNIQUE(id,project_id);
ALTER TABLE workspace_run_authorizations ADD CONSTRAINT uq_skill_execution_delegation
    UNIQUE(run_id,actor_user_id,actor_org_id,actor_source);
ALTER TABLE skill_activations
    ADD COLUMN verified_run_id UUID,
    ADD COLUMN actor_user_id UUID REFERENCES app_users(id),
    ADD COLUMN actor_source VARCHAR(20);
-- Old rows remain historical records. A run_id alone does not prove which
-- caller activated a package; do not infer authority from its alleged Run.
ALTER TABLE skill_activations ADD CONSTRAINT skill_activation_verified_shape CHECK(
    (verified_run_id IS NULL AND actor_user_id IS NULL AND actor_source IS NULL)
    OR (verified_run_id IS NOT NULL AND run_id IS NOT NULL AND verified_run_id=run_id
        AND actor_user_id IS NOT NULL AND actor_source IS NOT NULL AND actor_source IN ('LOCAL','OIDC'))
);
ALTER TABLE skill_activations ADD CONSTRAINT skill_activation_verified_run_project
    FOREIGN KEY(verified_run_id,project_id) REFERENCES agent_runs(id,project_id) ON DELETE CASCADE;
ALTER TABLE skill_activations ADD CONSTRAINT skill_activation_verified_delegation
    FOREIGN KEY(verified_run_id,actor_user_id,org_id,actor_source)
    REFERENCES workspace_run_authorizations(run_id,actor_user_id,actor_org_id,actor_source) ON DELETE CASCADE;
CREATE UNIQUE INDEX uq_skill_verified_activation ON skill_activations(verified_run_id,version_id)
    WHERE verified_run_id IS NOT NULL;
DO $$ BEGIN
    IF NOT EXISTS(SELECT 1 FROM security_mode WHERE id=1 AND protocol_version=9) THEN
        RAISE EXCEPTION 'V88 requires security protocol v9 registration';
    END IF;
    UPDATE security_mode SET protocol_version=10,updated_at=now() WHERE id=1;
END $$;
