-- MODEL PROVIDERS: a row per user-defined provider.
--
-- V32/V33/V34 modelled every provider as its OWN singleton table plus a
-- hardcoded enum, so a second (or tenth) user-defined gateway could not be
-- represented at all. This migration introduces the row-per-provider store that
-- the "quick add" entry writes to.
--
-- Deliberately NOT a full cut-over: OpenCode Zen and OpenRouter keep their
-- dedicated tables, services and REST endpoints for now, because neither is a
-- generic OpenAI-compatible client — OpenCode Zen issues absolute direct calls
-- to https://opencode.ai/zen/v1 with its own request headers, and OpenRouter
-- runs its own qualification pass. Those stay special-cased; what becomes
-- uniform is the provider LIST, the settings card and the activation target.
--
-- `preset` is carried on every row so the two presets can be folded into this
-- same table later without another schema change.

CREATE TABLE model_providers (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Preset kind. Always CUSTOM for rows created here; the column exists so
    -- preset rows can move in later without a rewrite.
    preset VARCHAR(30) NOT NULL DEFAULT 'CUSTOM',
    display_name VARCHAR(64) NOT NULL,
    api_format VARCHAR(30) NOT NULL,
    base_url TEXT NOT NULL,
    api_key TEXT NULL,
    masked_suffix VARCHAR(8) NULL,
    selected_model VARCHAR(255) NULL,
    model_source VARCHAR(16) NOT NULL DEFAULT 'DISCOVERED',
    config_revision BIGINT NOT NULL DEFAULT 1,
    validated_revision BIGINT NULL,
    position INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    validated_at TIMESTAMP NULL
);

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'model_providers_preset_check') THEN
        ALTER TABLE model_providers
            ADD CONSTRAINT model_providers_preset_check
            CHECK (preset IN ('OPENCODE_ZEN', 'OPENROUTER', 'CUSTOM'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'model_providers_api_format_check') THEN
        ALTER TABLE model_providers
            ADD CONSTRAINT model_providers_api_format_check
            CHECK (api_format IN ('CHAT_COMPLETIONS', 'RESPONSES', 'ANTHROPIC_MESSAGES'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'model_providers_model_source_check') THEN
        ALTER TABLE model_providers
            ADD CONSTRAINT model_providers_model_source_check
            CHECK (model_source IN ('DISCOVERED', 'MANUAL'));
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'model_providers_display_name_check') THEN
        ALTER TABLE model_providers
            ADD CONSTRAINT model_providers_display_name_check
            CHECK (BTRIM(display_name) <> '');
    END IF;
END
$$;

-- The legacy single Custom profile becomes the first row, so an existing
-- installation keeps its working credential and model selection.
INSERT INTO model_providers (preset, display_name, api_format, base_url, api_key, masked_suffix,
                             selected_model, model_source, config_revision, validated_revision,
                             position, created_at, updated_at, validated_at)
SELECT 'CUSTOM',
       COALESCE(NULLIF(BTRIM(s.display_name), ''), 'Custom'),
       s.api_format,
       s.base_url,
       s.api_key,
       s.masked_suffix,
       s.selected_model,
       COALESCE(s.model_source, 'DISCOVERED'),
       s.config_revision,
       s.validated_revision,
       0,
       s.created_at,
       s.updated_at,
       s.validated_at
FROM custom_provider_settings s
WHERE s.singleton_id = 1;

-- Active provider must be addressable by row, otherwise N user-defined
-- providers are indistinguishable. The legacy `active_provider` preset code is
-- kept so existing readers (and the preset activation path) keep working, which
-- means the CHECK constraint pinning it to exactly three values has to go.
ALTER TABLE model_provider_settings
    DROP CONSTRAINT IF EXISTS model_provider_settings_active_provider_check;

ALTER TABLE model_provider_settings
    ADD COLUMN IF NOT EXISTS active_provider_id UUID NULL;

UPDATE model_provider_settings m
SET active_provider_id = (SELECT p.id FROM model_providers p ORDER BY p.position, p.created_at LIMIT 1)
WHERE m.singleton_id = 1
  AND m.active_provider = 'CUSTOM'
  AND m.active_provider_id IS NULL;

CREATE INDEX IF NOT EXISTS model_providers_position_idx ON model_providers (preset, position, created_at);
