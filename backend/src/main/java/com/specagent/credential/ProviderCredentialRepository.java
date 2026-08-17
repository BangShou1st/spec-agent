package com.specagent.credential;

import com.specagent.common.Maps;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

@Repository
public class ProviderCredentialRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final RowMapper<ProviderCredential> rowMapper;

    public ProviderCredentialRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = (rs, rowNum) -> new ProviderCredential(
                rs.getString("provider"),
                rs.getString("encrypted_secret"),
                rs.getString("masked_suffix"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at") == null
                        ? null
                        : rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * Inserts or replaces the credential of one provider. Persisting a new
     * credential for an existing provider replaces the old ciphertext.
     */
    public void upsert(ProviderCredential credential) {
        String sql = """
                INSERT INTO provider_credentials (provider, encrypted_secret, masked_suffix,
                                                  created_at, updated_at)
                VALUES (:provider, :encryptedSecret, :maskedSuffix, :createdAt, :updatedAt)
                ON CONFLICT (provider) DO UPDATE
                    SET encrypted_secret = EXCLUDED.encrypted_secret,
                        masked_suffix = EXCLUDED.masked_suffix,
                        updated_at = EXCLUDED.updated_at
                """;
        Instant now = Instant.now();
        jdbcTemplate.update(sql, Maps.of(
                "provider", credential.provider(),
                "encryptedSecret", credential.encryptedSecret(),
                "maskedSuffix", credential.maskedSuffix(),
                "createdAt", Timestamp.from(now),
                "updatedAt", Timestamp.from(now)));
    }

    public Optional<ProviderCredential> findByProvider(String provider) {
        String sql = "SELECT * FROM provider_credentials WHERE provider = :provider";
        return jdbcTemplate.query(sql, Maps.of("provider", provider), rowMapper).stream().findFirst();
    }
}