-- An active target can only refer to a provider in the same organization.
ALTER TABLE model_providers ADD CONSTRAINT model_providers_id_org_unique UNIQUE (id, org_id);
ALTER TABLE model_provider_settings ADD CONSTRAINT active_model_same_org
    FOREIGN KEY (active_provider_id, org_id) REFERENCES model_providers (id, org_id)
    ON DELETE SET NULL (active_provider_id);
