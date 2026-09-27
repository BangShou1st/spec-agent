package com.specagent.modelsettings;

import com.specagent.common.Maps;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * 文件名:JdbcOpenCodeSettingsRepository.java
 *
 * 用途:OpenCodeSettingsRepository 的 JDBC 实现。OpenCode(Zen)提供商的设置
 * 在库中只有一行(singleton_id = 1),保存密钥(含脱敏后缀)与选中模型。
 */
@Repository
public class JdbcOpenCodeSettingsRepository implements OpenCodeSettingsRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ModelCredentialCrypto crypto;
    private final RowMapper<OpenCodeSettings> rowMapper;

    public JdbcOpenCodeSettingsRepository(NamedParameterJdbcTemplate jdbc,
                                          ModelCredentialCrypto crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.rowMapper = (rs, rowNum) -> new OpenCodeSettings(
                crypto.decrypt(rs.getString("api_key")),
                rs.getString("masked_suffix"),
                rs.getString("selected_model"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }

    @Override
    public Optional<OpenCodeSettings> find() {
        return jdbc.query("SELECT * FROM opencode_settings WHERE singleton_id = 1", Maps.of(), rowMapper)
                .stream().findFirst();
    }

    @Override
    public void upsert(OpenCodeSettings settings) {
        Instant now = settings.updatedAt() == null ? Instant.now() : settings.updatedAt();
        jdbc.update("""
                INSERT INTO opencode_settings (singleton_id, api_key, masked_suffix, selected_model,
                                               created_at, updated_at)
                VALUES (1, :apiKey, :maskedSuffix, :selectedModel, :createdAt, :updatedAt)
                ON CONFLICT (singleton_id) DO UPDATE SET
                    api_key = EXCLUDED.api_key,
                    masked_suffix = EXCLUDED.masked_suffix,
                    selected_model = EXCLUDED.selected_model,
                    updated_at = EXCLUDED.updated_at
                """, Maps.of(
                "apiKey", crypto.encrypt(settings.apiKey()),
                "maskedSuffix", settings.maskedSuffix(),
                "selectedModel", settings.selectedModel(),
                "createdAt", Timestamp.from(settings.createdAt() == null ? now : settings.createdAt()),
                "updatedAt", Timestamp.from(now)));
    }
}
