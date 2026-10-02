-- Exact descriptors are host frozen and hashed with the cross-language canonical JSON convention.
ALTER TABLE ga_executions ADD COLUMN capability_catalog TEXT NOT NULL DEFAULT '[]'
    CHECK (octet_length(capability_catalog) <= 65536);
ALTER TABLE ga_executions ADD COLUMN catalog_hash VARCHAR(64) NOT NULL
    DEFAULT '4f53cda18c2baa0c0354bb5f9a3ecbe5ed12ab4d8e11ba873c2f11161202b945'
    CHECK (catalog_hash ~ '^[0-9a-f]{64}$');
