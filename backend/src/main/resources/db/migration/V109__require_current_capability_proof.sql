-- V108 retired the built-in router identity and scrubbed inherited validation
-- flags once, at the upgrade moment. That is not sufficient: LegacyModelConnectionMigrator
-- is an ApplicationRunner, so it imports legacy rows AFTER Flyway has finished.
-- Any row imported by a startup that ran between V108 and this release therefore
-- still carries the old singleton's validated_revision, even though it has no
-- capability proof for its own (org_id, provider_id, config_revision).
--
-- Re-apply the same predicate V108 uses, but scoped to imported rows so the
-- statement is surgical and idempotent. A row that has a real STRUCTURED
-- VERIFIED proof at its current revision keeps its validated state; everything
-- else must be re-proven before it can be activated. This touches only the
-- derived summary columns — never credentials, endpoints, models, or revisions.
UPDATE model_providers p SET validated_revision=NULL,validated_at=NULL
WHERE p.legacy_source IS NOT NULL
  AND p.validated_revision IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM model_connection_capabilities c
    WHERE c.org_id=p.org_id AND c.provider_id=p.id AND c.config_revision=p.config_revision
      AND c.capability='STRUCTURED' AND c.status='VERIFIED'
);
