package com.specagent.runtime.execution;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:ExecutionModeAdmissionTest.java
 *
 * 测试目标:数据库范围的模式准入协议(design §11"禁止新旧调度器混跑")。
 *
 * <p>直接用真实构造的组件(非 Spring bean)对真实 PostgreSQL 验证协议本身:
 * <ol>
 *   <li><b>登记表准入</b>:模式一致时放行;已登记为另一种模式时拒绝启动。</li>
 *   <li><b>共享/排他互斥(旧二进制边界)</b>:任意外部连接持有排他
 *       advisory lock(等价于一个旧单执行器实例)时,任务级准入失败;
 *       两个任务级准入(共享锁)可以共存——多 Worker 舰队的正常形态。</li>
 *   <li><b>持有中丢失 fail-closed</b>:共享锁随连接消失后,验证循环闩锁,
 *       {@code owned()} 永久转 false,绝不静默重取。</li>
 * </ol>
 *
 * <p>测试 profile 下 worker.enabled=false,测试上下文自身不持有任何执行器
 * advisory lock,因此本类可以在同一 JVM 内安全模拟外部持有者。
 */
@SpringBootTest
@ActiveProfiles("test")
class ExecutionModeAdmissionTest {

    @Autowired DataSource dataSource;
    @Autowired NamedParameterJdbcTemplate jdbc;

    private final java.util.List<ExecutionModeAdmission> admissions = new java.util.ArrayList<>();
    private final java.util.List<Connection> rawConnections = new java.util.ArrayList<>();

    @AfterEach
    void release() {
        for (ExecutionModeAdmission admission : admissions) {
            try {
                admission.destroy();
            } catch (RuntimeException ignored) {
            }
        }
        admissions.clear();
        for (Connection connection : rawConnections) {
            try {
                connection.close();
            } catch (SQLException ignored) {
            }
        }
        rawConnections.clear();
        // 测试中改写过登记行的,恢复成 SINGLE_EXECUTOR 基线,避免影响其他测试。
        jdbc.update("UPDATE runtime_execution_mode SET mode = 'SINGLE_EXECUTOR', "
                + "updated_by = 'test-reset' WHERE id = 1", Map.of());
    }

    private ExecutionModeAdmission admit(boolean perTask) {
        ExecutionModeAdmission admission = new ExecutionModeAdmission(dataSource, perTask, true);
        admissions.add(admission);
        return admission;
    }

    /**
     * 模拟一个旧单执行器实例:独立会话持有排他 advisory lock。
     * 必须用<b>池外</b>连接:池化连接 close 只是归还,会话级 advisory lock
     * 会随留在池中的物理会话继续存活。
     */
    private Connection holdExclusiveLease() throws SQLException {
        var hikari = dataSource.unwrap(com.zaxxer.hikari.HikariDataSource.class);
        Connection connection = java.sql.DriverManager.getConnection(
                hikari.getJdbcUrl(), hikari.getUsername(), hikari.getPassword());
        connection.setAutoCommit(true);
        try (Statement statement = connection.createStatement()) {
            var rs = statement.executeQuery(
                    "SELECT pg_try_advisory_lock(" + RuntimeExecutionProtocol.EXECUTOR_LOCK_KEY + ")");
            rs.next();
            if (!rs.getBoolean(1)) {
                connection.close();
                throw new IllegalStateException("test fixture could not take the exclusive lease");
            }
        }
        rawConnections.add(connection);
        return connection;
    }

    @Test
    void singleExecutorInstanceIsAdmittedWhenDatabaseMatches() {
        ExecutionModeAdmission admission = admit(false);
        assertThat(admission.owned()).isTrue();
        assertThat(admission.admittedMode()).isEqualTo("SINGLE_EXECUTOR");
        assertThat(admission.isLost()).isFalse();
    }

    /** 把登记行切到 PER_TASK:任务级准入的前置(生产走维护窗口,测试直接改行)。 */
    private void admitDatabaseToPerTask() {
        jdbc.update("UPDATE runtime_execution_mode SET mode = 'PER_TASK', "
                + "updated_by = 'test' WHERE id = 1", Map.of());
    }

    @Test
    void perTaskInstanceIsAdmittedAndSharedLocksCoexist() throws SQLException {
        try (Connection lease = holdExclusiveLease()) {
            // 排他锁在场(旧单执行器):任务级准入必须被拒绝——任何一端都不许混跑。
            admitDatabaseToPerTask();
            assertThatThrownBy(() -> admit(true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("single-executor");
        }
        // 排他锁释放(旧实例已退出):两个任务级准入共存,共享锁之间兼容。
        ExecutionModeAdmission first = admit(true);
        ExecutionModeAdmission second = admit(true);
        assertThat(first.owned()).isTrue();
        assertThat(second.owned()).isTrue();
        assertThat(first.admittedMode()).isEqualTo("PER_TASK");
    }

    @Test
    void singleExecutorInstanceRefusesADatabaseAdmittedToPerTaskFleet() {
        jdbc.update("UPDATE runtime_execution_mode SET mode = 'PER_TASK', "
                + "updated_by = 'test' WHERE id = 1", Map.of());
        assertThatThrownBy(() -> admit(false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PER_TASK")
                .hasMessageContaining("SINGLE_EXECUTOR");
    }

    @Test
    void perTaskInstanceRefusesADatabaseAdmittedToSingleExecutor() {
        jdbc.update("UPDATE runtime_execution_mode SET mode = 'SINGLE_EXECUTOR', "
                + "updated_by = 'test' WHERE id = 1", Map.of());
        assertThatThrownBy(() -> admit(true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SINGLE_EXECUTOR")
                .hasMessageContaining("PER_TASK");
    }

    @Test
    void lostSharedLockLatchesClosedAndNeverReacquires() throws SQLException {
        admitDatabaseToPerTask();
        ExecutionModeAdmission admission = admit(true);
        assertThat(admission.owned()).isTrue();

        // 模拟会话级共享锁随连接消失:直接关闭准入组件持有的连接
        // (等价于数据库重启、后端被终止)。
        admission.destroy();
        admissions.remove(admission);

        // verifyStillAdmitted 观察到连接消失 → 永久闩锁。
        admission.verifyStillAdmitted();
        assertThat(admission.isLost())
                .as("a lost admission must latch closed like the executor lease")
                .isTrue();
        assertThat(admission.owned()).isFalse();
        assertThat(admission.getLostReason()).isNotBlank();

        // 闩锁后再次验证也绝不恢复:丢失是永久的(与 ExecutorLease 相同语义)。
        admission.verifyStillAdmitted();
        assertThat(admission.owned()).isFalse();
    }

    @Test
    void disabledAdmissionIsInertEvenAgainstForeignLocks() throws SQLException {
        try (Connection lease = holdExclusiveLease()) {
            ExecutionModeAdmission admission = new ExecutionModeAdmission(dataSource, true, false);
            assertThat(admission.owned())
                    .as("test-only opt-out must skip the whole protocol")
                    .isTrue();
        }
    }
}
