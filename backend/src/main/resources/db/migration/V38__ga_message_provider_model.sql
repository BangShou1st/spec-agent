-- Per-message model accounting: which provider/model generated this
-- assistant message. Nullable because USER messages and historical rows
-- have no such attribution.
ALTER TABLE global_assistant_messages ADD COLUMN provider_label VARCHAR(120);
ALTER TABLE global_assistant_messages ADD COLUMN model_id VARCHAR(200);
