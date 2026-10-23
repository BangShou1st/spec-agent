-- Provenance makes the encrypted application migration idempotent per org.
-- Ciphertext is rebound by Java; copying it between tables breaks AES-GCM AAD.
ALTER TABLE model_providers ADD COLUMN legacy_source VARCHAR(40);
CREATE UNIQUE INDEX model_provider_legacy_source_unique
    ON model_providers(org_id, legacy_source) WHERE legacy_source IS NOT NULL;
-- No FK: a user-deleted imported connection must not be recreated on restart.
CREATE TABLE model_connection_legacy_imports (
    org_id UUID NOT NULL,
    source VARCHAR(40) NOT NULL,
    provider_id UUID NOT NULL,
    PRIMARY KEY(org_id,source)
);
