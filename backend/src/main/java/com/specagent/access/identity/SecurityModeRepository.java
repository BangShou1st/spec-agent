package com.specagent.access.identity;

import java.util.Optional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 文件名:SecurityModeRepository.java
 *
 * 用途:身份模式与安全协议版本的数据库登记读写(第二阶段 §11)。
 *
 * 与 P1 的 {@code runtime_execution_mode} 同构:本地配置是<b>每实例</b>的,
 * 本身无法阻止两个实例带着不同模式连到同一个数据库。登记行是数据库范围的
 * 事实源,实例启动时与本地配置比对,不一致即拒绝启动。
 */
@Repository
public class SecurityModeRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public SecurityModeRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public record RegisteredMode(String mode, int protocolVersion, String issuerUri) {
    }

    public Optional<RegisteredMode> find() {
        return jdbc.query("""
                SELECT mode, protocol_version, issuer_uri FROM security_mode WHERE id = 1
                """, java.util.Map.of(),
                (rs, rowNum) -> new RegisteredMode(
                        rs.getString("mode"),
                        rs.getInt("protocol_version"),
                        rs.getString("issuer_uri")))
                .stream().findFirst();
    }

    public void update(String mode, int protocolVersion, String issuerUri) {
        jdbc.update("""
                UPDATE security_mode
                SET mode = :mode, protocol_version = :protocolVersion,
                    issuer_uri = :issuerUri, updated_at = now()
                WHERE id = 1
                """, java.util.Map.of("mode", mode,
                "protocolVersion", protocolVersion,
                "issuerUri", issuerUri == null ? "" : issuerUri));
    }
}
