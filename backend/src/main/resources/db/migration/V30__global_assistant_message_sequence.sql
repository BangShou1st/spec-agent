-- Stable per-thread message sequence for conversation ordering and the
-- rolling-summary cursor. created_at stays as time metadata; the random
-- UUID can no longer reorder history when timestamps tie.
ALTER TABLE global_assistant_messages ADD COLUMN sequence BIGINT;
-- One deterministic initial numbering for existing rows; physical row order
-- is never trusted.
WITH ranked AS (
    SELECT id, ROW_NUMBER() OVER (
        PARTITION BY thread_id
        ORDER BY created_at, id
    ) AS rn
    FROM global_assistant_messages
)
UPDATE global_assistant_messages m SET sequence = r.rn FROM ranked r WHERE m.id = r.id;
ALTER TABLE global_assistant_messages ALTER COLUMN sequence SET NOT NULL;
ALTER TABLE global_assistant_messages ADD CONSTRAINT uq_ga_messages_thread_sequence UNIQUE (thread_id, sequence);
CREATE INDEX idx_ga_messages_thread_sequence ON global_assistant_messages (thread_id, sequence);
