package com.specagent.support;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * 文件名:OpenCodeSettingsCleanup.java
 *
 * 测试辅助类:清空全局 OpenCode 设置表,让每个测试都从明确的空状态开始。
 */
public final class OpenCodeSettingsCleanup {

    private OpenCodeSettingsCleanup() {
    }

    public static void clear(NamedParameterJdbcTemplate jdbcTemplate) {
        jdbcTemplate.getJdbcTemplate().update("DELETE FROM opencode_settings");
    }
}
