-- Stage C follow-up migration: existing compatibility suites stayed green
-- with the additive V10 columns, so the legacy `question NOT NULL` constraint
-- can be relaxed. Non-question workspace units (KNOWLEDGE drafts, RESOURCE,
-- ARTIFACT) carry their payload in `content` and may leave `question` null.
-- INTERACTION/QUESTION nodes keep populating `question`; the Runtime still
-- enforces a non-blank question at creation time for that subtype.
ALTER TABLE nodes ALTER COLUMN question DROP NOT NULL;
