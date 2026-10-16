-- OpenCode Zen free models can be selected and used without a paid-model API key.
ALTER TABLE opencode_settings ALTER COLUMN api_key DROP NOT NULL;
ALTER TABLE opencode_settings ALTER COLUMN masked_suffix DROP NOT NULL;
