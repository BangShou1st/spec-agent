package com.specagent.connection.credentials;

import com.specagent.access.AuthorizationScopeRepository;
import com.specagent.access.AuthorizationScopes;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Narrow startup-only data transformation; never provides a runtime legacy decryption fallback. */
@Component
public class ConnectionCredentialEncryptionMigrator implements ApplicationRunner {
    private final NamedParameterJdbcTemplate jdbc;
    private final ConnectionCredentialCrypto crypto;
    private final TransactionTemplate transactions;
    private final AuthorizationScopeRepository scopes;

    public ConnectionCredentialEncryptionMigrator(NamedParameterJdbcTemplate jdbc,
            ConnectionCredentialCrypto crypto, org.springframework.transaction.PlatformTransactionManager transactions,
            AuthorizationScopeRepository scopes) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.transactions = new TransactionTemplate(transactions);
        this.scopes = scopes;
    }

    @Override public void run(ApplicationArguments args) {
        String cursor = "";
        while (true) {
            var rows = jdbc.query("""
                    SELECT ref,org_id,connection_id FROM connection_credentials
                    WHERE ref>:cursor ORDER BY ref LIMIT 100
                    """, Map.of("cursor", cursor), (rs, n) -> new Identity(rs.getString("ref"),
                    rs.getObject("org_id", UUID.class), rs.getObject("connection_id", UUID.class)));
            if (rows.isEmpty()) return;
            crypto.requireAvailable();
            for (Identity identity : rows) transactions.executeWithoutResult(ignored -> migrate(identity));
            cursor = rows.getLast().ref();
        }
    }

    private void migrate(Identity identity) {
        var keys = List.of(AuthorizationScopes.org(identity.org()));
        scopes.ensure(keys);
        if (scopes.lockShared(keys).size() != keys.size())
            throw new IllegalStateException("MCP credential migration authorization scope unavailable");
        var parents = jdbc.queryForList("SELECT id FROM connections WHERE id=:connection AND org_id=:org FOR UPDATE",
                Map.of("connection", identity.connection(), "org", identity.org()), UUID.class);
        if (parents.isEmpty()) return; // Concurrent parent deletion: there is no credential left to migrate.
        var params = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                .addValue("ref", identity.ref()).addValue("org", identity.org()).addValue("connection", identity.connection());
        var values = jdbc.queryForList("""
                SELECT encrypted_secret FROM connection_credentials
                WHERE ref=:ref AND org_id=:org AND connection_id=:connection
                """, params, String.class);
        if (values.isEmpty()) return;
        String original = values.getFirst();
        String secret = crypto.decryptForMigration(original, identity.org(), identity.connection(), identity.ref());
        if (crypto.currentEnvelope(original)) return; // Authenticated above, including unchanged rows.
        params.addValue("original", original).addValue("cipher",
                crypto.encrypt(secret, identity.org(), identity.connection(), identity.ref()));
        int changed = jdbc.update("""
                UPDATE connection_credentials SET encrypted_secret=:cipher,updated_at=now()
                WHERE ref=:ref AND org_id=:org AND connection_id=:connection AND encrypted_secret=:original
                """, params);
        if (changed == 1) jdbc.update("""
                UPDATE connections SET configuration_version=configuration_version+1,updated_at=now()
                WHERE id=:connection AND org_id=:org AND credential_ref=:ref
                """, params);
    }

    private record Identity(String ref, UUID org, UUID connection) { }
}
