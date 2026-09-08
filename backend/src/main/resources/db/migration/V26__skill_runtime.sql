-- Skill Runtime persistence (Phase 2).
-- Design invariants:
--   * skills.skill_id is the stable external identity (never changes on upgrade).
--   * skill_versions are immutable: version_no increments per install, content_hash
--     is a unique immutable identity, and a file list/bytes may never be rewritten.
--   * skill_package_files holds the full immutable package content with per-file
--     sha256; nothing lives on the local filesystem, so import validation and
--     resource reads never touch host paths.
--   * skill_staged_imports is the staging/review queue: content is validated and
--     hashed before any enabled state, and install is a separate explicit step.
--   * No script/dependency execution ever happens during validate/install; there
--     is deliberately no column or table for "run hooks".

CREATE TABLE skills (
    id                  UUID PRIMARY KEY,
    skill_id            TEXT NOT NULL UNIQUE,
    name                TEXT NOT NULL,
    description         TEXT NOT NULL DEFAULT '',
    source_kind         TEXT NOT NULL,
    source_identity     TEXT NOT NULL,
    current_version_id  UUID,
    enabled             BOOLEAN NOT NULL DEFAULT false,
    created_at          TIMESTAMP NOT NULL,
    updated_at          TIMESTAMP NOT NULL
);

-- Only one enabled Skill may carry a given display name; a disabled
-- (or differently-sourced) same-name Skill stays installable and visible in
-- management, but cannot be enabled while the name is taken (no semantic
-- ambiguity between two enabled skills with the same name).
CREATE UNIQUE INDEX uq_skills_enabled_name ON skills (name) WHERE enabled = true;

CREATE TABLE skill_versions (
    id              UUID PRIMARY KEY,
    skill_row_id    UUID NOT NULL REFERENCES skills (id),
    version_no      INT NOT NULL,
    content_hash    TEXT NOT NULL UNIQUE,
    manifest        TEXT NOT NULL,
    instructions    TEXT NOT NULL,
    source_identity TEXT NOT NULL,
    file_count      INT NOT NULL,
    total_bytes     BIGINT NOT NULL,
    created_at      TIMESTAMP NOT NULL,
    UNIQUE (skill_row_id, version_no)
);

CREATE TABLE skill_package_files (
    id            UUID PRIMARY KEY,
    version_id    UUID NOT NULL REFERENCES skill_versions (id),
    relative_path TEXT NOT NULL,
    kind          TEXT NOT NULL,
    size_bytes    BIGINT NOT NULL,
    sha256        TEXT NOT NULL,
    content       BYTEA,
    UNIQUE (version_id, relative_path)
);

CREATE TABLE skill_staged_imports (
    id               UUID PRIMARY KEY,
    source_kind      TEXT NOT NULL,
    source_identity  TEXT NOT NULL,
    manifest         TEXT NOT NULL,
    file_entries     TEXT NOT NULL,
    total_bytes      BIGINT NOT NULL,
    file_count       INT NOT NULL,
    content_hash     TEXT NOT NULL,
    status           TEXT NOT NULL,
    rejected_reason  TEXT,
    created_at       TIMESTAMP NOT NULL,
    installed_at     TIMESTAMP
);

CREATE TABLE skill_staged_files (
    id              UUID PRIMARY KEY,
    staged_import_id UUID NOT NULL REFERENCES skill_staged_imports (id),
    relative_path   TEXT NOT NULL,
    sha256          TEXT NOT NULL,
    content         BYTEA,
    UNIQUE (staged_import_id, relative_path)
);

CREATE TABLE skill_activations (
    id               UUID PRIMARY KEY,
    project_id       UUID NOT NULL REFERENCES projects (id),
    run_id           UUID,
    skill_id         TEXT NOT NULL,
    version_id       UUID NOT NULL REFERENCES skill_versions (id),
    source_identity  TEXT NOT NULL,
    content_hash     TEXT NOT NULL,
    created_at       TIMESTAMP NOT NULL
);

CREATE INDEX idx_skill_activations_project ON skill_activations (project_id, created_at DESC);