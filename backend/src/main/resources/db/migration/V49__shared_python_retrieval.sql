-- Same derived index: host grants/jobs/profile generations, no parallel vector store.
CREATE SEQUENCE retrieval_source_version_seq;
ALTER TABLE retrieval_entries
    ADD COLUMN corpus_id UUID,
    ADD COLUMN source_version VARCHAR(128) NOT NULL DEFAULT nextval('retrieval_source_version_seq')::text,
    ADD COLUMN profile_id VARCHAR(64),
    ADD COLUMN index_generation UUID,
    ADD COLUMN pending_embedding VECTOR,
    ADD COLUMN pending_profile_id VARCHAR(64),
    ADD COLUMN pending_generation UUID;
UPDATE retrieval_entries SET corpus_id=project_id;
ALTER TABLE retrieval_entries ALTER COLUMN corpus_id SET NOT NULL;
ALTER TABLE retrieval_entries ALTER COLUMN project_id DROP NOT NULL;
ALTER TABLE retrieval_entries DROP CONSTRAINT ck_retrieval_entries_scope;
ALTER TABLE retrieval_entries ADD CONSTRAINT ck_retrieval_entries_scope CHECK(scope IN ('ROUTE','PROJECT','RESOURCE','HELP'));
ALTER TABLE retrieval_entries ADD CONSTRAINT ck_retrieval_entries_corpus CHECK
    ((project_id IS NOT NULL AND corpus_id=project_id AND scope<>'HELP') OR
     (project_id IS NULL AND scope='HELP' AND source_kind='HELP_CHUNK' AND route_id IS NULL));
CREATE UNIQUE INDEX uq_retrieval_help_source ON retrieval_entries(corpus_id,source_ref) WHERE project_id IS NULL;

CREATE FUNCTION retrieval_projection_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    NEW.corpus_id := COALESCE(NEW.corpus_id,NEW.project_id);
    IF TG_OP='UPDATE' AND ROW(NEW.content_hash,NEW.content,NEW.authority,NEW.scope,NEW.route_id,NEW.metadata,NEW.retracted_at)
        IS DISTINCT FROM ROW(OLD.content_hash,OLD.content,OLD.authority,OLD.scope,OLD.route_id,OLD.metadata,OLD.retracted_at) THEN
        NEW.source_version := nextval('retrieval_source_version_seq')::text;
        NEW.pending_embedding := NULL; NEW.pending_profile_id := NULL; NEW.pending_generation := NULL;
        IF ROW(NEW.content_hash,NEW.content) IS DISTINCT FROM ROW(OLD.content_hash,OLD.content) THEN
            NEW.embedding := NULL; NEW.profile_id := NULL; NEW.index_generation := NULL;
            NEW.embedding_status := CASE WHEN NEW.retracted_at IS NULL THEN 'PENDING' ELSE 'UNAVAILABLE' END;
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER retrieval_projection_version BEFORE INSERT OR UPDATE ON retrieval_entries
    FOR EACH ROW EXECUTE FUNCTION retrieval_projection_version();

CREATE TABLE retrieval_index_generations (
    id UUID PRIMARY KEY,
    corpus_id UUID NOT NULL,
    profile_id VARCHAR(64) NOT NULL CHECK(profile_id ~ '^[0-9a-f]{64}$'),
    state VARCHAR(20) NOT NULL CHECK(state IN ('PREPARING','ACTIVE','RETIRED','FAILED')),
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE retrieval_index_heads (
    corpus_id UUID PRIMARY KEY,
    active_generation UUID NOT NULL REFERENCES retrieval_index_generations(id),
    profile_id VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL DEFAULT 0
);
CREATE TABLE retrieval_scope_grants (
    id UUID PRIMARY KEY,
    request_id UUID NOT NULL UNIQUE,
    corpus_id UUID NOT NULL,
    project_id UUID REFERENCES projects(id) ON DELETE CASCADE,
    route_id UUID REFERENCES routes(id) ON DELETE CASCADE,
    workload_kind VARCHAR(30) NOT NULL,
    workload_id UUID NOT NULL,
    epoch BIGINT NOT NULL CHECK(epoch>0),
    version BIGINT NOT NULL DEFAULT 1,
    profile_id VARCHAR(64) NOT NULL,
    index_generation UUID NOT NULL REFERENCES retrieval_index_generations(id),
    deadline TIMESTAMPTZ NOT NULL,
    scope JSONB NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_retrieval_grant_expiry ON retrieval_scope_grants(deadline);
CREATE TABLE retrieval_index_jobs (
    id UUID PRIMARY KEY,
    corpus_id UUID NOT NULL,
    index_generation UUID NOT NULL REFERENCES retrieval_index_generations(id),
    profile_id VARCHAR(64) NOT NULL,
    epoch BIGINT NOT NULL DEFAULT 1 CHECK(epoch>0),
    lease_id UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    state VARCHAR(20) NOT NULL CHECK(state IN ('CLAIMED','SUCCEEDED','FAILED','STALE')),
    deadline TIMESTAMPTZ NOT NULL,
    request JSONB NOT NULL,
    result_hash VARCHAR(64),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
