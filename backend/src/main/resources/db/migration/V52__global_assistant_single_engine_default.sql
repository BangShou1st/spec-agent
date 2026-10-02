-- Historical Java runs retain their recorded engine. Only future defaults change.
ALTER TABLE global_assistant_runs ALTER COLUMN engine_version SET DEFAULT 'langchain-ga.v1';
