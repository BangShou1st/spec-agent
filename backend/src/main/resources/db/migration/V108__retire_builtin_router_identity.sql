-- Existing UUIDs, endpoints and encrypted credentials keep their identity.
UPDATE model_providers SET preset='CUSTOM' WHERE preset='OPENROUTER';
UPDATE model_provider_settings SET active_provider='CUSTOM'
WHERE active_provider='OPENROUTER' AND active_provider_id IS NOT NULL;
-- Older row validation did not include selected_model in revision invalidation.
-- Require a new exact-revision proof instead of inheriting that unsafe flag.
UPDATE model_providers p SET validated_revision=NULL,validated_at=NULL
WHERE p.preset='CUSTOM' AND NOT EXISTS (
    SELECT 1 FROM model_connection_capabilities c
    WHERE c.org_id=p.org_id AND c.provider_id=p.id AND c.config_revision=p.config_revision
      AND c.capability='STRUCTURED' AND c.status='VERIFIED'
);
ALTER TABLE model_providers DROP CONSTRAINT model_providers_preset_check;
ALTER TABLE model_providers ADD CONSTRAINT model_providers_preset_check
    CHECK(preset IN ('OPENCODE_ZEN','KILO','CUSTOM'));
