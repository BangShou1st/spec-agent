-- Review fix: one autonomous continuation child per parent run.
--
-- The deterministic continue:<parentRunId> key plus the project-scoped
-- idempotency unique index already arbitrate concurrent creators at the
-- request level. This partial unique index additionally guards the chain
-- structure itself: a Run A must never fork into two children (Run B and
-- Run C). Chain roots and pre-continuation rows keep parent_run_id NULL
-- and are unaffected by the partial index.
--
-- This is unrelated to graph route sharing: two routes may share one node,
-- but one parent run may still own exactly one continuation child.
CREATE UNIQUE INDEX IF NOT EXISTS idx_agent_runs_single_continuation_child
    ON agent_runs (parent_run_id)
    WHERE parent_run_id IS NOT NULL;
