package com.specagent.agent.runtime;

import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationVersion;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** JDBC mocks only: verifies the fail-closed callback, not PostgreSQL/Flyway lock lifetime. */
class LegacyBindingMigrationGuardTest {
    @Test void historicalMigrationChecksumsAreFrozenAndForwardProtectionDoesNotBackfillData() throws Exception {
        var directory = java.nio.file.Path.of("src/main/resources/db/migration");
        var expected = java.util.Map.of(
                "V98__agent_run_model_binding.sql", "393b9ffd37d4063bb66c3b019d39b22b38c67e053f4e74ac25cb5add9e5cede1",
                "V99__legacy_run_model_binding_fail_closed.sql", "fddcd23d863962ab89ebb56f715930fa20a665a836a044dfaedf636b9fd94bba");
        for (var entry : expected.entrySet()) {
            var source = java.nio.file.Files.readString(directory.resolve(entry.getKey())).replace("\r\n", "\n");
            var digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertThat(java.util.HexFormat.of().formatHex(digest)).isEqualTo(entry.getValue());
        }
        var forward = java.nio.file.Files.readString(directory.resolve("V100__protect_existing_run_model_binding.sql"));
        assertThat(forward).contains("OLD.model_binding_fingerprint IS NOT NULL",
                "NEW.model_binding_fingerprint IS DISTINCT FROM OLD.model_binding_fingerprint",
                "RUN_MODEL_BINDING_IMMUTABLE")
                .doesNotContain("UPDATE agent_runs", "DELETE FROM", "TRUNCATE");
        // This checks source preservation/intent, not execution of a PostgreSQL trigger.
    }
    final LegacyBindingMigrationGuard guard = new LegacyBindingMigrationGuard();
    final Context context = mock(Context.class);
    final MigrationInfo migration = mock(MigrationInfo.class);
    final Connection connection = mock(Connection.class);
    final Statement statement = mock(Statement.class);
    final ResultSet rows = mock(ResultSet.class);

    void setup(boolean overwrite) throws Exception {
        when(context.getMigrationInfo()).thenReturn(migration);
        when(migration.getVersion()).thenReturn(MigrationVersion.fromVersion("99"));
        when(context.getConnection()).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(rows);
        when(rows.next()).thenReturn(true);
        when(rows.getBoolean(1)).thenReturn(overwrite);
    }

    @Test void existingBindingBlocksV99WithoutPrintingRowOrDriverData() throws Exception {
        setup(true);
        assertThatThrownBy(() -> guard.handle(Event.BEFORE_EACH_MIGRATE, context))
                .isInstanceOf(FlywayException.class).hasMessageContaining("V99_BINDING_OVERWRITE_BLOCKED");
        var order = inOrder(statement);
        order.verify(statement).execute("LOCK TABLE agent_runs IN SHARE ROW EXCLUSIVE MODE");
        order.verify(statement).executeQuery(contains("model_binding_fingerprint IS NOT NULL"));
        verify(connection, never()).commit();
    }

    @Test void safeDataCanBeCheckedRepeatedlyAndDoesNotRewriteAnyRows() throws Exception {
        setup(false);
        guard.handle(Event.BEFORE_EACH_MIGRATE, context);
        guard.handle(Event.BEFORE_EACH_MIGRATE, context);
        verify(statement, times(2)).executeQuery(contains("status NOT IN ('created', 'completed', 'failed')"));
        verify(statement, never()).executeUpdate(anyString());
    }

    @Test void historicalV99AndForwardMigrationsAreNotReplayedByTheGuard() throws Exception {
        setup(false);
        assertThat(guard.supports(Event.AFTER_EACH_MIGRATE, context)).isFalse();
        when(migration.getVersion()).thenReturn(MigrationVersion.fromVersion("100"));
        guard.handle(Event.BEFORE_EACH_MIGRATE, context);
        verifyNoInteractions(connection);
    }

    @Test void failedCheckCanBeRetriedButNeverAllowedOnAnAutoCommitConnection() throws Exception {
        setup(false);
        when(statement.executeQuery(anyString())).thenThrow(new java.sql.SQLException("secret fixture"))
                .thenReturn(rows);
        assertThatThrownBy(() -> guard.handle(Event.BEFORE_EACH_MIGRATE, context))
                .hasMessage("V99_BINDING_GUARD_QUERY_FAILED").hasMessageNotContaining("secret fixture");
        assertThatCode(() -> guard.handle(Event.BEFORE_EACH_MIGRATE, context)).doesNotThrowAnyException();
        when(connection.getAutoCommit()).thenReturn(true);
        assertThatThrownBy(() -> guard.handle(Event.BEFORE_EACH_MIGRATE, context))
                .hasMessage("V99_BINDING_GUARD_REQUIRES_TRANSACTION");
    }

    @Test void runStatusContractSeparatesApprovalPhaseFromTerminalAndQueuedRuns() {
        assertThat(java.util.Arrays.stream(AgentRunStatus.values()).filter(AgentRunStatus::isTerminal))
                .containsExactly(AgentRunStatus.COMPLETED, AgentRunStatus.FAILED);
        assertThat(java.util.Arrays.stream(AgentRunStatus.values()).filter(s -> !s.isTerminal()))
                .containsExactly(AgentRunStatus.CREATED, AgentRunStatus.RUNNING, AgentRunStatus.CONTEXT_BUILT,
                        AgentRunStatus.MODEL_CALLED, AgentRunStatus.REFLECTED, AgentRunStatus.PERSISTED);
        assertThatThrownBy(() -> AgentRunStatus.fromCode("awaiting_approval"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
