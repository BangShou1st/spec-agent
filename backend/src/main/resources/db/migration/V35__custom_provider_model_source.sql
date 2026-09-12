-- MODEL PROVIDERS V1 round 2: persist how the Custom selected model was
-- chosen so manual-model mode survives reloads deterministically.
-- Existing rows default to DISCOVERED; the persisted-value display fix
-- covers them regardless.
ALTER TABLE custom_provider_settings
    ADD COLUMN IF NOT EXISTS model_source VARCHAR(16) NOT NULL DEFAULT 'DISCOVERED';
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'custom_provider_model_source_check'
    ) THEN
        ALTER TABLE custom_provider_settings
            ADD CONSTRAINT custom_provider_model_source_check
            CHECK (model_source IN ('DISCOVERED', 'MANUAL'));
    END IF;
END
$$;
