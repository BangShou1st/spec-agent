-- Phase 5.1: Provider credentials
-- One installation-wide encrypted credential per provider.
-- The plaintext secret never enters the database; only the encrypted secret
-- and a masked suffix for status display are stored.

CREATE TABLE provider_credentials (
    provider VARCHAR(32) PRIMARY KEY,
    encrypted_secret TEXT NOT NULL,
    masked_suffix VARCHAR(4) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);