-- MS-C1: provider-private continuation state is host-owned and encrypted.
-- One row is the sidecar of exactly one successful MODEL ledger call. Rows are
-- never updated in place; source identity is immutable.
CREATE TABLE ga_model_continuations (
    id UUID PRIMARY KEY,
    org_id UUID NOT NULL REFERENCES organizations(id) ON DELETE CASCADE,
    thread_id UUID NOT NULL REFERENCES global_assistant_threads(id) ON DELETE CASCADE,
    source_run_id UUID NOT NULL REFERENCES ga_executions(run_id) ON DELETE CASCADE,
    source_call_id UUID NOT NULL,
    provider VARCHAR(40) NOT NULL,
    model TEXT NOT NULL,
    settings_revision TEXT NOT NULL,
    tool_call_ids TEXT NOT NULL CHECK (octet_length(tool_call_ids) <= 2048),
    payload_count SMALLINT NOT NULL CHECK (payload_count BETWEEN 1 AND 5),
    payload_length INTEGER NOT NULL CHECK (payload_length BETWEEN 1 AND 4096),
    payload TEXT NOT NULL CHECK (octet_length(payload) <= 8192),
    state_version SMALLINT NOT NULL DEFAULT 1 CHECK (state_version = 1),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_ga_model_continuation_source UNIQUE (source_run_id, source_call_id),
    CONSTRAINT ck_ga_model_continuation_tool_ids CHECK (tool_call_ids ~ '^\["[A-Za-z0-9_-]{1,128}"(,"[A-Za-z0-9_-]{1,128}")*\]$')
);
CREATE INDEX idx_ga_model_continuation_thread ON ga_model_continuations(thread_id, created_at DESC);

COMMENT ON TABLE ga_model_continuations IS
    'Encrypted provider continuation sidecar; the opaque ref is valid only inside Java';
