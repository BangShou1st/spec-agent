-- Product-owned Kilo Gateway settings (third model provider), one row per
-- organization like the other singleton provider tables. Free Kilo models are
-- anonymously usable, so api_key / masked_suffix are nullable by design; the key
-- is stored in the trusted local database and never returned by read
-- projections. org_id carries no DEFAULT (V92 removed the legacy default) so a
-- missing owner can never be silently repaired to the local organization. The
-- active_provider column in model_provider_settings has had no value CHECK since
-- V37, so the new 'KILO' preset needs no constraint change.
CREATE TABLE kilo_settings (
    org_id UUID NOT NULL REFERENCES organizations(id),
    singleton_id SMALLINT NOT NULL CHECK (singleton_id = 1),
    api_key TEXT NULL,
    masked_suffix VARCHAR(8) NULL,
    selected_model VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (org_id, singleton_id)
);
