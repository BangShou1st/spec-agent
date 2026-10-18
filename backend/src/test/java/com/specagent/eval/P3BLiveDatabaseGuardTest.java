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
}
