-- Installation services are independent of chat providers. Empty rows are authoritative tombstones.
CREATE TABLE search_settings (
 singleton_id INTEGER PRIMARY KEY CHECK(singleton_id=1), enabled BOOLEAN NOT NULL,
 api_key TEXT, revision BIGINT NOT NULL DEFAULT 1, source VARCHAR(20) NOT NULL DEFAULT 'DATABASE',
 tested_revision BIGINT, test_code VARCHAR(80), tested_at TIMESTAMPTZ
);
CREATE TABLE embedding_service_settings (
 singleton_id INTEGER PRIMARY KEY CHECK(singleton_id=1), revision BIGINT NOT NULL,
 config JSONB NOT NULL, api_key TEXT, candidate_profile VARCHAR(64),
 tested_revision BIGINT, test_code VARCHAR(80), tested_at TIMESTAMPTZ
);
CREATE TABLE embedding_profiles (
 profile_id VARCHAR(64) PRIMARY KEY CHECK(profile_id ~ '^[a-f0-9]{64}$'),
 semantic JSONB NOT NULL, config JSONB NOT NULL, service_revision BIGINT NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE embedding_service_revisions (
 revision BIGINT PRIMARY KEY, config JSONB NOT NULL, api_key TEXT,
 revoked BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE embedding_probes (
 id UUID PRIMARY KEY, config JSONB NOT NULL, credential_hash VARCHAR(64) NOT NULL,
 dimensions INTEGER NOT NULL, model_digest TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
-- Job status is durable; vectors remain exclusively in retrieval_entries.
CREATE TABLE embedding_rebuilds (
 id UUID PRIMARY KEY REFERENCES retrieval_index_generations(id), corpus_id UUID NOT NULL,
 profile_id VARCHAR(64) NOT NULL REFERENCES embedding_profiles(profile_id),
 expected_head_version BIGINT NOT NULL, state VARCHAR(20) NOT NULL
 CHECK(state IN ('QUEUED','RUNNING','READY','FAILED','ACTIVE')),
 processed INTEGER NOT NULL DEFAULT 0, total INTEGER NOT NULL DEFAULT 0,
 error_code VARCHAR(80), created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
