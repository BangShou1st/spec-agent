-- Product-owned, installation-global OpenCode settings.
-- The local single-user product deliberately stores the provider key in the
-- trusted local database; API/read projections never expose api_key.
DROP TABLE IF EXISTS provider_credentials;

CREATE TABLE opencode_settings (
    singleton_id SMALLINT PRIMARY KEY CHECK (singleton_id = 1),
    api_key TEXT NOT NULL,
    masked_suffix VARCHAR(4) NOT NULL,
    selected_model VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
