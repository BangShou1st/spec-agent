-- MODEL PROVIDERS V1: OpenRouter singleton with revision validation.
CREATE TABLE openrouter_settings (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    api_key TEXT NOT NULL,
    masked_suffix VARCHAR(8) NOT NULL,
    selected_model VARCHAR(255) NOT NULL,
    config_revision BIGINT NOT NULL DEFAULT 1,
    validated_revision BIGINT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    validated_at TIMESTAMP NULL
);
