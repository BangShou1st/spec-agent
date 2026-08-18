package com.specagent.support;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Test-only helper that removes any OpenCode credential from the test database.
 *
 * <p>Tests that assert "no credential" (NOT_CONFIGURED paths, empty-state
 * checks) must not depend on the local development database being clean: a
 * live smoke or a manual seed may have left an encrypted OpenCode credential
 * behind. Call {@link #clear} in {@code @BeforeEach} so the test owns its
 * credential state.
 */
public final class OpenCodeCredentialCleanup {

    private OpenCodeCredentialCleanup() {
    }

    public static void clear(NamedParameterJdbcTemplate jdbcTemplate) {
        jdbcTemplate.update("DELETE FROM provider_credentials WHERE provider = 'opencode'", java.util.Map.of());
    }
}
