package com.specagent.connection.credentials;

import com.specagent.access.AuthorizationScopeRepository;
import com.specagent.access.AuthorizationScopes;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ConnectionCredentialEncryptionMigratorTest {
    private static final String A = Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));
    private static final String B = Base64.getEncoder().encodeToString("abcdef0123456789abcdef0123456789".getBytes(StandardCharsets.UTF_8));
    private final UUID org = UUID.randomUUID(), connection = UUID.randomUUID();
    private final String ref = "cred:" + connection + ":" + UUID.randomUUID();
    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final AuthorizationScopeRepository scopes = mock(AuthorizationScopeRepository.class);
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

    @SuppressWarnings("unchecked")
    private ConnectionCredentialEncryptionMigrator runner(ConnectionCredentialCrypto crypto, String stored) throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getString("ref")).thenReturn(ref);
        when(row.getObject("org_id", UUID.class)).thenReturn(org);
        when(row.getObject("connection_id", UUID.class)).thenReturn(connection);
        AtomicInteger pages = new AtomicInteger();
        doAnswer(call -> {
            if (pages.getAndIncrement() != 0) return List.of();
            RowMapper<?> mapper = call.getArgument(2);
            return List.of(mapper.mapRow(row, 0));
        }).when(jdbc).query(anyString(), anyMap(), any(RowMapper.class));
        when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(scopes.lockShared(List.of(AuthorizationScopes.org(org)))).thenReturn(List.of(AuthorizationScopes.org(org)));
        when(jdbc.queryForList(anyString(), anyMap(), eq(UUID.class))).thenReturn(List.of(connection));
        when(jdbc.queryForList(anyString(), any(SqlParameterSource.class), eq(String.class))).thenReturn(List.of(stored));
        return new ConnectionCredentialEncryptionMigrator(jdbc, crypto, transactions, scopes);
    }

    @Test void rotationAuthenticatesOldScopeAndBumpsOnlySelectedConnectionAfterCas() throws Exception {
        var before = new ConnectionCredentialCrypto(A, "");
        var after = new ConnectionCredentialCrypto(B, A);
        var runner = runner(after, before.encrypt("old-token", org, connection, ref));
        when(jdbc.update(startsWith("UPDATE connection_credentials"), any(SqlParameterSource.class))).thenAnswer(call -> {
            MapSqlParameterSource params = call.getArgument(1);
            assertThat(after.decrypt((String)params.getValue("cipher"), org, connection, ref)).isEqualTo("old-token");
            assertThat(params.getValue("org")).isEqualTo(org);
            return 1;
        });
        runner.run(null);
        verify(jdbc).update(contains("credential_ref=:ref"), any(SqlParameterSource.class));
        verify(transactions).commit(any());
    }

    @Test void currentEnvelopeIsAuthenticatedButNotRewritten() throws Exception {
        var crypto = new ConnectionCredentialCrypto(A, "");
        runner(crypto, crypto.encrypt("token", org, connection, ref)).run(null);
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
        verify(transactions).commit(any());
    }

    @Test void copiedCiphertextFailsStartupWithoutOverwritingOriginal() throws Exception {
        var crypto = new ConnectionCredentialCrypto(A, "");
        var runner = runner(crypto, crypto.encrypt("token", UUID.randomUUID(), connection, ref));
        assertThatThrownBy(() -> runner.run(null)).isInstanceOf(IllegalStateException.class).hasNoCause();
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
        verify(transactions).rollback(any());
    }

    @Test void failedCompareAndSetDoesNotInvalidateAnotherWritersConnection() throws Exception {
        var before = new ConnectionCredentialCrypto(A, "");
        var after = new ConnectionCredentialCrypto(B, A);
        runner(after, before.encrypt("token", org, connection, ref)).run(null);
        verify(jdbc, never()).update(contains("UPDATE connections"), any(SqlParameterSource.class));
    }

    @Test void persistedSecretsWithoutPrimaryKeyRefuseStartup() throws Exception {
        var runner = runner(new ConnectionCredentialCrypto("", ""), "legacy-envelope");
        assertThatThrownBy(() -> runner.run(null)).hasMessageContaining("SPEC_AGENT_SECRET_MASTER_KEY");
        verifyNoInteractions(transactions);
        verify(jdbc, never()).update(anyString(), any(SqlParameterSource.class));
    }
}
