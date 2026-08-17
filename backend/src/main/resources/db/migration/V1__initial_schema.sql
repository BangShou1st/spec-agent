-- Initial schema for Spec Agent
-- This is a baseline migration - Runtime tables will be added in Phase 2

-- Placeholder table to verify Flyway works
CREATE TABLE IF NOT EXISTS schema_version (
    version VARCHAR(50) PRIMARY KEY,
    applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

INSERT INTO schema_version (version) VALUES ('1.0.0')
ON CONFLICT (version) DO NOTHING;