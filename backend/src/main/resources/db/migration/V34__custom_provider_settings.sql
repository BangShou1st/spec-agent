-- MODEL PROVIDERS V1: single Custom profile with revision validation.
CREATE TABLE custom_provider_settings (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    api_format VARCHAR(30) NOT NULL CHECK (api_format IN ('CHAT_COMPLETIONS', 'RESPONSES', 'ANTHROPIC_MESSAGES')),
    base_url TEXT NOT NULL,
    api_key TEXT NULL,
    masked_suffix VARCHAR(8) NULL,
    selected_model VARCHAR(255) NOT NULL,
    config_revision BIGINT NOT NULL DEFAULT 1,
    validated_revision BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    validated_at TIMESTAMP NULL
);
