-- Blocker 6: symmetric relation uniqueness backstop.
--
-- RELATED_TO and CONFLICTS_WITH are directionless facts: A <-> B is the same
-- relation as B <-> A. The application canonicalizes their endpoints to
-- (minId, maxId) before writing (GraphInvariantValidator.endpointsCanonicalized),
-- but the database had no corresponding backstop, so a reverse-duplicate ACTIVE
-- row could coexist with the canonical one.
--
-- This migration:
--   1. Preflight: fail closed if any unordered node pair already has more than
--      one ACTIVE symmetric relation. Such an ambiguity is unrecoverable
--      automatically and must be repaired by hand (actionable error).
--   2. Canonicalize: a single ACTIVE symmetric row stored in reverse order
--      (source > target) is swapped to (min, max) so it matches the index
--      identity below and the application-layer canonicalization.
--   3. Add a partial unique index over the canonical identity for ACTIVE
--      symmetric relations. Directional types (DEPENDS_ON / DERIVED_FROM /
--      SUPPORTS) keep the original directed uniqueness untouched.
--
-- Idempotent: re-running after success is a no-op (the index already exists and
-- the preflight/canonicalization find nothing to do).

-- ---------------------------------------------------------------------------
-- Step 1: preflight — refuse to proceed when an unordered pair carries more
-- than one ACTIVE symmetric relation (ambiguous; needs data repair).
-- ---------------------------------------------------------------------------
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT project_id,
               relation_type,
               LEAST(source_node_id, target_node_id)   AS lo,
               GREATEST(source_node_id, target_node_id) AS hi,
               COUNT(*) AS cnt
        FROM node_relations
        WHERE status = 'ACTIVE'
          AND relation_type IN ('RELATED_TO', 'CONFLICTS_WITH')
        GROUP BY project_id,
                 relation_type,
                 LEAST(source_node_id, target_node_id),
                 GREATEST(source_node_id, target_node_id)
        HAVING COUNT(*) > 1
    LOOP
        RAISE EXCEPTION
            'SYMMETRIC_RELATION_CONFLICT: project % has % ACTIVE % relations on the same unordered node pair (% , %). Manual data repair is required (collapse to a single canonical row) before this migration can proceed.',
            r.project_id, r.cnt, r.relation_type, r.lo, r.hi;
    END LOOP;
END $$;

-- ---------------------------------------------------------------------------
-- Step 2: safe canonicalization — a lone ACTIVE symmetric row stored in reverse
-- order is swapped to (min, max). The preflight above guarantees at most one
-- ACTIVE row per unordered pair, so the swap cannot collide.
-- ---------------------------------------------------------------------------
UPDATE node_relations
SET source_node_id = LEAST(source_node_id, target_node_id),
    target_node_id = GREATEST(source_node_id, target_node_id)
WHERE status = 'ACTIVE'
  AND relation_type IN ('RELATED_TO', 'CONFLICTS_WITH')
  AND source_node_id > target_node_id;

-- ---------------------------------------------------------------------------
-- Step 3: partial unique index over the canonical identity for ACTIVE
-- symmetric relations. LEAST/GREATEST on uuid are supported by PostgreSQL and
-- preserve the same pairwise ordering as Java UUID.compareTo, so the database
-- identity matches the application-layer canonicalization exactly.
-- ---------------------------------------------------------------------------
CREATE UNIQUE INDEX IF NOT EXISTS idx_node_relations_symmetric_active_unique
    ON node_relations (
        project_id,
        relation_type,
        LEAST(source_node_id, target_node_id),
        GREATEST(source_node_id, target_node_id)
    )
    WHERE status = 'ACTIVE'
      AND relation_type IN ('RELATED_TO', 'CONFLICTS_WITH');
