CREATE TABLE authorization_revocation_outbox (
    id UUID PRIMARY KEY,
    actor_user_id UUID NOT NULL,
    org_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    processed_at TIMESTAMPTZ
);
CREATE INDEX idx_authorization_revocation_pending
    ON authorization_revocation_outbox(created_at,id) WHERE processed_at IS NULL;
