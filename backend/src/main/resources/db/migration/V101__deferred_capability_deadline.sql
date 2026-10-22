-- PENDING is an explicitly registered deferred job, never an alias for RUNNING.
-- Existing rows retain their status; old crash-window RUNNING rows are not promoted.
ALTER TABLE capability_invocations ADD COLUMN pending_deadline TIMESTAMPTZ NULL;
CREATE INDEX idx_capability_invocations_pending_deadline
    ON capability_invocations (pending_deadline, id) WHERE status = 'PENDING';
