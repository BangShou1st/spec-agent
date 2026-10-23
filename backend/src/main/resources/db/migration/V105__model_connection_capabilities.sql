CREATE TABLE model_connection_capabilities (
    org_id UUID NOT NULL,
    provider_id UUID NOT NULL,
    config_revision BIGINT NOT NULL,
    capability VARCHAR(30) NOT NULL CHECK (capability IN ('CHAT','STRUCTURED','NATIVE_TOOLS')),
    status VARCHAR(30) NOT NULL CHECK (status IN ('VERIFIED','UNSUPPORTED','TEMPORARILY_UNAVAILABLE')),
    checked_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY(org_id,provider_id,config_revision,capability),
    FOREIGN KEY(provider_id,org_id) REFERENCES model_providers(id,org_id) ON DELETE CASCADE
);
