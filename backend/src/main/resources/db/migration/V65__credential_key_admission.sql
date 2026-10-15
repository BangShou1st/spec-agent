CREATE TABLE credential_key_admission (
    id SMALLINT PRIMARY KEY CHECK(id=1),
    primary_key_fingerprint VARCHAR(64) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
