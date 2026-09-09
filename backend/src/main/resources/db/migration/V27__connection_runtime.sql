-- Connection runtime persistence (Phase 3).
-- Design invariants:
--   * connections is the product concept (an external integration); MCP is a
--     protocol implementation behind it. A Connection row never stores a
--     plaintext secret — only a credentialRef into connection_credentials.
--   * connection_credentials stores AES-GCM-encrypted secrets (key from the
--     host environment, never in the DB); the plaintext never enters traces,
--     capability results, descriptors, or model-visible context.
--   * mcp_discovery_cache caches normalized primitives per connection so a
--     reconnect does not re-run discovery; refresh invalidates it.
--   * connection_kinds: SYSTEM_SUPPORTED (product-managed integrations) or
--     CUSTOM_MCP (user-supplied remote MCP server).

CREATE TABLE connections (
    id                UUID PRIMARY KEY,
    connection_id     TEXT NOT NULL UNIQUE,
    name              TEXT NOT NULL,
    kind              TEXT NOT NULL,            -- SYSTEM_SUPPORTED | CUSTOM_MCP
    status            TEXT NOT NULL,            -- CREATED | TESTED | CONNECTED | DISABLED | FAILED
    enabled           BOOLEAN NOT NULL DEFAULT false,
    config            TEXT NOT NULL,            -- JSON: serverUrl, transport, headers (no secrets)
    credential_ref    TEXT,                     -- FK-ish reference into connection_credentials.ref (no plaintext)
    last_error        TEXT,
    created_at        TIMESTAMP NOT NULL,
    updated_at        TIMESTAMP NOT NULL
);

CREATE UNIQUE INDEX uq_connections_enabled_name ON connections (name) WHERE enabled = true;

CREATE TABLE connection_credentials (
    ref               TEXT PRIMARY KEY,
    connection_id     UUID NOT NULL REFERENCES connections (id),
    encrypted_secret  TEXT NOT NULL,
    masked_suffix     VARCHAR(8) NOT NULL,
    created_at        TIMESTAMP NOT NULL,
    updated_at        TIMESTAMP NOT NULL
);

CREATE TABLE mcp_discovery_cache (
    connection_id     UUID PRIMARY KEY REFERENCES connections (id),
    tools             TEXT NOT NULL,
    resources         TEXT NOT NULL,
    prompts           TEXT NOT NULL,
    fingerprint       TEXT NOT NULL,
    discovered_at     TIMESTAMP NOT NULL
);