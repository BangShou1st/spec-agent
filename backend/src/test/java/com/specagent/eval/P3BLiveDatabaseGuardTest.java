package com.specagent.eval;

import com.specagent.eval.EvalLiveIsolatedDatabase.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

/**
 * 文件名:P3BLiveDatabaseGuardTest.java
 *
 * 测试目标:P3-B live 评测的数据库误删保护。
 *
 * 核心判据:种子重置(<b>先 DELETE 再 INSERT</b>)只能发生在被明确指定的独立
 * live 评测库上。任何普通开发库、共享测试库或历史 P3-A/P3-B 库,以及"环境变量
 * 声明的是隔离库但实际连到别的库"的连接漂移,都必须在<b>第一次写之前</b>失败关闭,
 * 并保证<b>零行数据改变</b>。
 *
 * <p>命名刻意不带 {@code EvalLive} 前缀:这样它随 {@code evalBFast} 与
 * {@code testNonLive} 一起运行,而不是被 live 排除规则跳过。本类不触网、不连库。
 */
class P3BLiveDatabaseGuardTest {

    private static final String PREFLIGHT_URL = "jdbc:postgresql://unused/spec_agent_p3b_live_offline";

    @Test
    void preflightStaticFailureNeverOpensConnection() {
        var factory = mock(EvalLiveIsolatedDatabase.ConnectionFactory.class);
        assertThatThrownBy(() -> EvalLiveIsolatedDatabase.verifyBeforeContext(
                "jdbc:postgresql://unused/spec_agent", "offline", "dummy", "192.0.2.1", "5432", factory))
                .isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(factory);
    }

    @Test
    void preflightRequiresExplicitCredentialsAndEndpointBeforeConnecting() {
        var factory = mock(EvalLiveIsolatedDatabase.ConnectionFactory.class);
        for (String port : List.of("", "bad", "0", "65536")) {
            assertThatThrownBy(() -> EvalLiveIsolatedDatabase.verifyBeforeContext(
                    PREFLIGHT_URL, "offline", "dummy", "192.0.2.1", port, factory))
                    .isInstanceOf(IllegalStateException.class);
        }
        assertThatThrownBy(() -> EvalLiveIsolatedDatabase.verifyBeforeContext(
                PREFLIGHT_URL, null, null, null, null, factory)).isInstanceOf(IllegalStateException.class);
        org.mockito.Mockito.verifyNoInteractions(factory);
    }

    @Test
    void preflightConnectionFailureIsClosedWithoutLeakingDriverDetails() throws Exception {
        var factory = mock(EvalLiveIsolatedDatabase.ConnectionFactory.class);
        org.mockito.Mockito.when(factory.open(anyString(), anyString(), anyString()))
                .thenThrow(new java.sql.SQLException("sensitive driver details"));
        assertThatThrownBy(() -> EvalLiveIsolatedDatabase.verifyBeforeContext(
                PREFLIGHT_URL, "offline", "dummy", "192.0.2.1", "5432", factory))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("independent JDBC")
                .hasMessageNotContaining("sensitive").hasNoCause();
    }

    @Test
    void preflightChecksDatabaseAndSameNameServerDriftAndClosesResources() throws Exception {
        for (String mismatch : List.of("none", "database", "host", "port", "user", "empty")) {
            var connection = mock(java.sql.Connection.class);
            var statement = mock(java.sql.Statement.class);
            var rows = mock(java.sql.ResultSet.class);
            org.mockito.Mockito.when(connection.createStatement()).thenReturn(statement);
            org.mockito.Mockito.when(statement.executeQuery(anyString())).thenReturn(rows);
            org.mockito.Mockito.when(rows.next()).thenReturn(!mismatch.equals("empty"));
            org.mockito.Mockito.when(rows.getString("db")).thenReturn(mismatch.equals("database")
                    ? "spec_agent_test" : "spec_agent_p3b_live_offline");
            org.mockito.Mockito.when(rows.getString("usr")).thenReturn(mismatch.equals("user") ? "other" : "offline");
            org.mockito.Mockito.when(rows.getString("host")).thenReturn(mismatch.equals("host") ? "192.0.2.2" : "192.0.2.1");
            org.mockito.Mockito.when(rows.getInt("port")).thenReturn(mismatch.equals("port") ? 5433 : 5432);
            org.assertj.core.api.ThrowableAssert.ThrowingCallable check = () ->
                    EvalLiveIsolatedDatabase.verifyBeforeContext(PREFLIGHT_URL, "offline", "dummy",
                            "192.0.2.1", "5432", (url, user, password) -> connection);
            if (mismatch.equals("none")) assertThatCode(check).doesNotThrowAnyException();
            else assertThatThrownBy(check).isInstanceOf(IllegalStateException.class);
            verify(connection).setReadOnly(true);
            verify(connection).createStatement();
            verify(statement).setQueryTimeout(10);
            verify(rows).close();
            verify(statement).close();
            verify(connection).close();
            verifyNoMoreInteractions(connection);
        }
    }

    static final java.util.concurrent.atomic.AtomicInteger FRAMEWORK_STARTS = new java.util.concurrent.atomic.AtomicInteger();

    @org.springframework.test.context.ContextConfiguration(classes = SideEffectConfig.class)
    static class InvalidTargetBootstrap {
        @org.springframework.test.context.DynamicPropertySource
        static void database(org.springframework.test.context.DynamicPropertyRegistry registry) {
            EvalLiveIsolatedDatabase.verifyBeforeContext("jdbc:postgresql://unused/spec_agent",
                    "offline", "dummy", "192.0.2.1", "5432",
                    (url, user, password) -> { throw new AssertionError("must not open JDBC"); });
        }
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods = false)
    static class SideEffectConfig {
        @org.springframework.context.annotation.Bean
        Object frameworkDatabaseSideEffect() {
            FRAMEWORK_STARTS.incrementAndGet();
            throw new AssertionError("framework database initialization must not start");
        }
    }

    @Test
    void unauthorizedPlainSpringBootstrapFailsBeforeDynamicPropertiesAndBeans() {
        FRAMEWORK_STARTS.set(0);
        assertThatThrownBy(() -> new org.springframework.test.context.TestContextManager(InvalidTargetBootstrap.class)
                .prepareTestInstance(new InvalidTargetBootstrap()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("explicit -PdatabaseIntegration=true");
        assertThat(FRAMEWORK_STARTS.get()).isZero();
    }

    @Test
    void invalidDynamicPropertyTargetRejectsWithoutAnyJdbcConnection() {
        FRAMEWORK_STARTS.set(0);
        assertThatThrownBy(() -> InvalidTargetBootstrap.database(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ordinary development/test database");
        assertThat(FRAMEWORK_STARTS.get()).isZero();
    }

    private static DatabaseIdentity identity(String database) {
        return new DatabaseIdentity(database, "spec_agent", "127.0.0.1", 5434);
    }

    @Test
    void ordinaryDevelopmentAndTestDatabasesAreRefused() {
        for (String database : List.of(
                "spec_agent", "spec_agent_test", "spec_agent_p3a",
                "postgres", "template0", "template1")) {
            assertThatThrownBy(() -> EvalLiveIsolatedDatabase
                    .requireIsolatedLiveEvalDatabase(identity(database)))
                    .as("must refuse %s", database)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("refusing to seed");
        }
    }

    @Test
    void aHistoricalNonLiveEvalDatabaseIsRefusedToo() {
        assertThatThrownBy(() -> EvalLiveIsolatedDatabase
                .requireIsolatedLiveEvalDatabase(identity("spec_agent_p3b_20261017")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a designated P3-B live evaluation database");
    }

    @Test
    void onlyADesignatedLiveEvaluationDatabaseIsAccepted() {
        assertThatCode(() -> EvalLiveIsolatedDatabase
                .requireIsolatedLiveEvalDatabase(identity("spec_agent_p3b_live_20261018")))
                .doesNotThrowAnyException();
        assertThatCode(() -> EvalLiveIsolatedDatabase
                .requireIsolatedLiveEvalDatabase(
                        identity("spec_agent_p3b_live_20261019"), "spec_agent_p3b_live_20261019"))
                .doesNotThrowAnyException();
    }

    @Test
    void anUnresolvedConfiguredDatabaseNameIsRefused() {
        for (String configured : List.of("", "   ")) {
            assertThatThrownBy(() -> EvalLiveIsolatedDatabase.requireIsolatedLiveEvalDatabase(
                    identity("spec_agent_p3b_live_20261019"), configured))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("configured database name is undeterminable");
        }
        assertThatThrownBy(() -> EvalLiveIsolatedDatabase.requireIsolatedLiveEvalDatabase(
                identity("spec_agent_p3b_live_20261019"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("configured database name is undeterminable");
    }

    /** 环境变量声明的是隔离库、实际连到的却是别的库:必须拒绝(不信任客户端字符串)。 */
    @Test
    void aConfiguredNameThatDoesNotMatchTheRealConnectionIsRefused() {
        assertThatThrownBy(() -> EvalLiveIsolatedDatabase.requireIsolatedLiveEvalDatabase(
                identity("spec_agent_p3b_live_20261018"), "spec_agent"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match the connected database");
    }

    @Test
    void anUndeterminableIdentityIsRefused() {
        assertThatThrownBy(() -> EvalLiveIsolatedDatabase.requireIsolatedLiveEvalDatabase(
                new DatabaseIdentity(null, "spec_agent", "local", -1)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("undeterminable");
    }

    @Test
    void databaseNameIsParsedFromTheJdbcUrl() {
        assertThat(EvalLiveIsolatedDatabase.databaseNameFromUrl(
                "jdbc:postgresql://localhost:5434/spec_agent_p3b_live_20261018"))
                .isEqualTo("spec_agent_p3b_live_20261018");
        assertThat(EvalLiveIsolatedDatabase.databaseNameFromUrl(
                "jdbc:postgresql://localhost:5434/spec_agent_p3b_live_20261018?sslmode=disable"))
                .isEqualTo("spec_agent_p3b_live_20261018");
        assertThat(EvalLiveIsolatedDatabase.databaseNameFromUrl(null)).isNull();
        assertThat(EvalLiveIsolatedDatabase.databaseNameFromUrl("")).isNull();
    }

    /**
     * 错误库上的种子重置必须<b>零行数据改变</b>:身份校验失败后绝不再调用任何
     * {@code update}——DELETE / INSERT 一次都不发生。
     */
    @Test
    void seedingAWrongDatabaseChangesZeroRows() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doReturn(identity("spec_agent_test"))
                .when(jdbcTemplate).query(anyString(), any(ResultSetExtractor.class));

        assertThatThrownBy(() -> EvalLiveIsolatedDatabase.seedKiloActivation(
                jdbcTemplate, "spec_agent_test",
                "poolside/laguna-s-2.1:free", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refusing to seed");

        // 只发生了"读身份"这一次查询;没有任何 update(DELETE / INSERT)。
        verify(jdbcTemplate, times(1)).query(anyString(), any(ResultSetExtractor.class));
        verifyNoMoreInteractions(jdbcTemplate);
    }

    /** 连接声明的库名与真实库不一致时同样零写入。 */
    @Test
    void seedingWithAMismatchedConfiguredNameChangesZeroRows() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doReturn(identity("spec_agent_p3b_live_20261018"))
                .when(jdbcTemplate).query(anyString(), any(ResultSetExtractor.class));

        assertThatThrownBy(() -> EvalLiveIsolatedDatabase.seedKiloActivation(
                jdbcTemplate, "spec_agent",
                "poolside/laguna-s-2.1:free", UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match the connected database");

        verify(jdbcTemplate, times(1)).query(anyString(), any(ResultSetExtractor.class));
        verifyNoMoreInteractions(jdbcTemplate);
    }

    // -- verifyTargetDatabaseIsolation 离线单元测试 ------------------------------

    @Test
    void verifyTargetDatabaseIsolationRefusesMissingOrBlankUrl() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        for (String url : List.of("", "   ")) {
            assertThatThrownBy(() -> EvalLiveIsolatedDatabase.verifyTargetDatabaseIsolation(jdbcTemplate, url))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SPEC_AGENT_TARGET_DB_URL must be explicitly configured");
        }
        assertThatThrownBy(() -> EvalLiveIsolatedDatabase.verifyTargetDatabaseIsolation(jdbcTemplate, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPEC_AGENT_TARGET_DB_URL must be explicitly configured");
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void verifyTargetDatabaseIsolationRefusesForbiddenDatabase() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doReturn(identity("spec_agent"))
                .when(jdbcTemplate).query(anyString(), any(ResultSetExtractor.class));

        assertThatThrownBy(() -> EvalLiveIsolatedDatabase.verifyTargetDatabaseIsolation(
                jdbcTemplate, "jdbc:postgresql://localhost:5432/spec_agent"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ordinary development/test database");

        verify(jdbcTemplate, times(1)).query(anyString(), any(ResultSetExtractor.class));
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void verifyTargetDatabaseIsolationRefusesNonLiveEvalDatabase() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doReturn(identity("spec_agent_p3b_20261017"))
                .when(jdbcTemplate).query(anyString(), any(ResultSetExtractor.class));

        assertThatThrownBy(() -> EvalLiveIsolatedDatabase.verifyTargetDatabaseIsolation(
                jdbcTemplate, "jdbc:postgresql://localhost:5434/spec_agent_p3b_20261017"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a designated P3-B live evaluation database");

        verify(jdbcTemplate, times(1)).query(anyString(), any(ResultSetExtractor.class));
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void verifyTargetDatabaseIsolationRefusesMismatchedConnection() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doReturn(identity("spec_agent_p3b_live_actual"))
                .when(jdbcTemplate).query(anyString(), any(ResultSetExtractor.class));

        assertThatThrownBy(() -> EvalLiveIsolatedDatabase.verifyTargetDatabaseIsolation(
                jdbcTemplate, "jdbc:postgresql://localhost:5434/spec_agent_p3b_live_configured"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match the connected database");

        verify(jdbcTemplate, times(1)).query(anyString(), any(ResultSetExtractor.class));
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void verifyTargetDatabaseIsolationRefusesUndeterminableIdentity() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        doReturn(new DatabaseIdentity(null, "spec_agent", "local", -1))
                .when(jdbcTemplate).query(anyString(), any(ResultSetExtractor.class));

        assertThatThrownBy(() -> EvalLiveIsolatedDatabase.verifyTargetDatabaseIsolation(
                jdbcTemplate, "jdbc:postgresql://localhost:5434/spec_agent_p3b_live_20261020"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("undeterminable");

        verify(jdbcTemplate, times(1)).query(anyString(), any(ResultSetExtractor.class));
        verifyNoMoreInteractions(jdbcTemplate);
    }

    @Test
    void verifyTargetDatabaseIsolationAcceptsMatchingLiveDatabase() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DatabaseIdentity expected = identity("spec_agent_p3b_live_gemini_20261020");
        doReturn(expected)
                .when(jdbcTemplate).query(anyString(), any(ResultSetExtractor.class));

        DatabaseIdentity verified = EvalLiveIsolatedDatabase.verifyTargetDatabaseIsolation(
                jdbcTemplate, "jdbc:postgresql://localhost:5434/spec_agent_p3b_live_gemini_20261020");

        assertThat(verified).isEqualTo(expected);
        verify(jdbcTemplate, times(1)).query(anyString(), any(ResultSetExtractor.class));
        verifyNoMoreInteractions(jdbcTemplate);
    }

    // -- validateTargetDatabaseUrlStatic 离线静态预检查测试 ----------------------

    @Test
    void validateTargetDatabaseUrlStaticRefusesMissingOrBlank() {
        for (String url : List.of("", "   ")) {
            assertThatThrownBy(() -> EvalLiveIsolatedDatabase.validateTargetDatabaseUrlStatic(url))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SPEC_AGENT_TARGET_DB_URL must be explicitly configured");
        }
        assertThatThrownBy(() -> EvalLiveIsolatedDatabase.validateTargetDatabaseUrlStatic(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPEC_AGENT_TARGET_DB_URL must be explicitly configured");
    }

    @Test
    void validateTargetDatabaseUrlStaticRefusesUndeterminableDatabaseName() {
        for (String url : List.of("jdbc:postgresql://localhost:5434/", "not_a_valid_url")) {
            assertThatThrownBy(() -> EvalLiveIsolatedDatabase.validateTargetDatabaseUrlStatic(url))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("database name is undeterminable");
        }
    }

    @Test
    void validateTargetDatabaseUrlStaticRefusesForbiddenDatabases() {
        for (String db : List.of("spec_agent", "spec_agent_test", "spec_agent_p3a", "postgres")) {
            assertThatThrownBy(() -> EvalLiveIsolatedDatabase.validateTargetDatabaseUrlStatic(
                    "jdbc:postgresql://localhost:5434/" + db))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ordinary development/test database");
        }
    }

    @Test
    void validateTargetDatabaseUrlStaticRefusesNonLiveEvalDatabase() {
        assertThatThrownBy(() -> EvalLiveIsolatedDatabase.validateTargetDatabaseUrlStatic(
                "jdbc:postgresql://localhost:5434/spec_agent_p3b_20261017"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a designated P3-B live evaluation database");
    }

    @Test
    void validateTargetDatabaseUrlStaticAcceptsDesignatedLiveDatabase() {
        String dbName = EvalLiveIsolatedDatabase.validateTargetDatabaseUrlStatic(
                "jdbc:postgresql://localhost:5434/spec_agent_p3b_live_gemini_20261020?sslmode=disable");
        assertThat(dbName).isEqualTo("spec_agent_p3b_live_gemini_20261020");
    }
}
