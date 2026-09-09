package com.specagent.connection;

import com.specagent.connection.credentials.LocalAesSecretStore;
import com.specagent.common.Json;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SecretStore contract: AES-GCM round-trip, masked display suffix, plaintext
 * never persisted, unknown refs fail closed, deletion removes the row.
 */
@SpringBootTest
@ActiveProfiles("test")
class LocalAesSecretStoreTest {

    @Autowired
    private LocalAesSecretStore secretStore;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private NamedParameterJdbcTemplate namedJdbc;
    @Autowired
    private Json json;

    @Test
    void storeRoundTripsAndMasks() {
        UUID connectionId = insertConnectionRow();
        String ref = secretStore.store(connectionId, "super-secret-token-abc");
        assertThat(ref).startsWith("cred:");
        assertThat(ref).doesNotContain("super-secret-token-abc");

        assertThat(secretStore.resolve(ref)).isEqualTo("super-secret-token-abc");
        assertThat(secretStore.maskedSuffix(ref)).isEqualTo("****-abc");

        String encrypted = jdbcTemplate.queryForObject(
                "SELECT encrypted_secret FROM connection_credentials WHERE ref = ?",
                String.class, ref);
        assertThat(encrypted).doesNotContain("super-secret-token-abc");

        secretStore.delete(ref);
        assertThat(secretStore.maskedSuffix(ref)).isNull();
        jdbcTemplate.update("DELETE FROM connections WHERE id = ?", connectionId);
    }

    @Test
    void blankSecretsAreRejected() {
        assertThatThrownBy(() -> secretStore.store(UUID.randomUUID(), "  "))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownRefFailsClosed() {
        assertThat(secretStore.maskedSuffix("cred:missing:ref")).isNull();
    }

    @Test
    void storeIsDisabledWithoutMasterKey() {
        LocalAesSecretStore disabled =
                new LocalAesSecretStore(namedJdbc, json, "");
        assertThat(disabled.available()).isFalse();
        assertThatThrownBy(() -> disabled.store(UUID.randomUUID(), "x"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPEC_AGENT_SECRET_MASTER_KEY");
    }

    @Test
    void shortSecretsMaskFully() {
        UUID connectionId = insertConnectionRow();
        String ref = secretStore.store(connectionId, "abcd");
        assertThat(secretStore.maskedSuffix(ref)).isEqualTo("****");
        assertThat(secretStore.resolve(ref)).isEqualTo("abcd");
        secretStore.delete(ref);
        jdbcTemplate.update("DELETE FROM connections WHERE id = ?", connectionId);
    }

    private UUID insertConnectionRow() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO connections (id, connection_id, name, kind, status,"
                        + " enabled, config, credential_ref, last_error,"
                        + " created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())",
                id, "conn_" + id.toString().substring(0, 12),
                "secret-test-" + id.toString().substring(0, 8),
                "CUSTOM_MCP", "CREATED", false, "{}", null, null);
        return id;
    }
}
