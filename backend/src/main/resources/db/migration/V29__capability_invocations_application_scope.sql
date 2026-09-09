-- Global Assistant V1 application-scoped invocations (Slice A).
-- project.create/search/list_recent run before any target project exists,
-- so capability_invocations.project_id becomes nullable. FK stays: when
-- present it must reference a real project; no dummy project is used.
-- Existing project-scoped behavior is unchanged.
ALTER TABLE capability_invocations ALTER COLUMN project_id DROP NOT NULL;
