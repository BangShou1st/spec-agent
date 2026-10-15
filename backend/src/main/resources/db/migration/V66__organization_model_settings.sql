DO $$
DECLARE t TEXT;
BEGIN
    FOREACH t IN ARRAY ARRAY['model_providers','model_provider_settings','opencode_settings','openrouter_settings','custom_provider_settings'] LOOP
        EXECUTE format('ALTER TABLE %I ADD COLUMN org_id UUID NOT NULL DEFAULT %L REFERENCES organizations(id)',t,'00000000-0000-4000-8000-000000000001');
    END LOOP;
    FOREACH t IN ARRAY ARRAY['model_provider_settings','opencode_settings','openrouter_settings','custom_provider_settings'] LOOP
        EXECUTE format('ALTER TABLE %I DROP CONSTRAINT %I',t,t||'_pkey');
        EXECUTE format('ALTER TABLE %I ADD PRIMARY KEY(org_id,singleton_id)',t);
    END LOOP;
END $$;
CREATE INDEX idx_model_providers_org ON model_providers(org_id,preset,position,id);
