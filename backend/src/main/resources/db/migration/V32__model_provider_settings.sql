-- MODEL PROVIDERS V1: active provider singleton. Upgrade defaults to OPENCODE_ZEN.
CREATE TABLE model_provider_settings (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    active_provider VARCHAR(20) NOT NULL CHECK (active_provider IN ('OPENCODE_ZEN', 'OPENROUTER', 'CUSTOM')),
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
INSERT INTO model_provider_settings (singleton_id, active_provider, updated_at)
VALUES (1, 'OPENCODE_ZEN', CURRENT_TIMESTAMP)
ON CONFLICT (singleton_id) DO NOTHING;
