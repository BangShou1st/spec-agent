-- Custom provider gets a user-facing display name shown in the provider
-- pill/tab strip and the runtime banner (OpenCode Zen / OpenRouter are
-- presets with fixed names; Custom is user-named).
ALTER TABLE custom_provider_settings
    ADD COLUMN IF NOT EXISTS display_name VARCHAR(64) NULL;
