package com.specagent.support;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/** Test helper that makes global OpenCode settings state explicit per test. */
public final class OpenCodeSettingsCleanup {

    private OpenCodeSettingsCleanup() {
    }

    public static void clear(NamedParameterJdbcTemplate jdbcTemplate) {
        jdbcTemplate.getJdbcTemplate().update("DELETE FROM opencode_settings");
    }
}
