-- 发现缓存是可重建数据；旧缓存没有配置版本，不能猜测其授权来源。
DELETE FROM mcp_discovery_cache;
ALTER TABLE mcp_discovery_cache ADD COLUMN configuration_version BIGINT NOT NULL;

-- 包含复合 FK 的 ON DELETE SET NULL；任何凭证解绑都必须使旧会话失效。
CREATE FUNCTION bump_connection_configuration_version() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.config IS DISTINCT FROM OLD.config
       OR NEW.credential_ref IS DISTINCT FROM OLD.credential_ref
       OR NEW.auth_kind IS DISTINCT FROM OLD.auth_kind
       OR NEW.enabled IS DISTINCT FROM OLD.enabled THEN
        NEW.configuration_version := GREATEST(NEW.configuration_version, OLD.configuration_version + 1);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER connections_configuration_version
BEFORE UPDATE ON connections
FOR EACH ROW EXECUTE FUNCTION bump_connection_configuration_version();
