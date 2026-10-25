ALTER TABLE model_provider_settings DROP CONSTRAINT active_model_same_org;
ALTER TABLE model_provider_settings ADD CONSTRAINT active_model_same_org
    FOREIGN KEY(active_provider_id,org_id) REFERENCES model_providers(id,org_id);
