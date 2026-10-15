-- SearchSettings writes its organization explicitly, just like model settings.
-- This was the last legacy organization DEFAULT found by the schema-wide audit.
ALTER TABLE search_settings ALTER COLUMN org_id DROP DEFAULT;
