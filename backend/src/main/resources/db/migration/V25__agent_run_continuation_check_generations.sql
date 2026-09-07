-- Slice 3C hardening: generation-gated continuation outbox.
--
-- run_id + processed_at cannot distinguish an old request from a newer one
-- (approval re-request ABA: PARKED_APPROVAL later accepted must reopen
-- evaluation while a stale generation-1 completion is still in flight).
-- request_generation increments on every request; completion marks exactly
-- one generation, so a superseded generation marks 0 rows and the newer
-- generation stays pending until recovery converges it.
ALTER TABLE agent_run_continuation_checks
    ADD COLUMN IF NOT EXISTS request_generation BIGINT NOT NULL DEFAULT 1;
