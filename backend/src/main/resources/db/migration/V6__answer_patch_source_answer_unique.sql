-- One immutable Answer has at most one persisted AnswerPatch.
-- Do not repair historical data here: duplicate history is a correctness
-- failure that must stop the migration and be investigated explicitly.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM answer_patches
        GROUP BY source_answer_id
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION
            'Cannot add answer patch uniqueness: historical duplicate source_answer_id rows exist';
    END IF;
END
$$;

CREATE UNIQUE INDEX uq_answer_patches_source_answer
    ON answer_patches (source_answer_id);
