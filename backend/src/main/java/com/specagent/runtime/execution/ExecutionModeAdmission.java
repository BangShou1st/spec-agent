package com.specagent.runtime.execution;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 文件名:ExecutionModeAdmission.java
 *
 * 用途:数据库范围的调度模式准入协议(design §11"禁止新旧调度器混跑"的强制形式)。
 *
 * <p><b>问题。</b>{@code spec.agent.execution.multi-worker-enabled} 是<b>每实例</b>
 * 的本地开关:两个实例带着不同配置连到同一个数据库时,本地开关互相不可见——
 * 单执行器实例不理解 task token,会破坏任务级所有权(design §11)。
 *
 * <p><b>协议(两层,互补)。</b>
 *
 * <ol>
 *   <li><b>模式登记表</b>({@code runtime_execution_mode},单行):本组件启动时
 *       原子校验"数据库被准入的模式"与本实例的本地模式一致;不一致立即拒绝
 *       启动(fail-fast,带着明确的补救指引)。模式切换必须走维护窗口:停止
 *       全部实例 → 显式改写该行 → 启动新舰队(design §11)。这一层覆盖
 *       <b>新二进制</b>之间的配置分歧,包括 API-only 实例(它与 Worker 的
 *       模式必须一致,否则入队路径与消费路径不匹配,任务会静默饿死)。</li>
 *   <li><b>共享/排他 advisory lock</b>(与 {@code ExecutorLease} 同一把键):
 *       任务级实例(含 API-only)在整个进程生命周期内持有
 *       {@code pg_advisory_lock_shared(LOCK_KEY)};单执行器执行器实例(旧或新)
 *       持有同一键的<b>排他</b>锁。共享与排他互斥,因此:
 *       <ul>
 *         <li>旧单执行器实例企图进入一个任务级舰队:排他锁取不到 → 启动失败
 *             (它现有的错误文案"Another executor already owns this database");</li>
 *         <li>任务级实例企图进入一个旧单执行器实例的数据库:共享锁取不到 →
 *             本组件拒绝启动。</li>
 *       </ul>
 *       这一层覆盖<b>旧二进制</b>边界——旧实例不理解任何新协议,但它本来就
 *       要取排他锁,而共享锁恰好与它互斥。多个任务级实例之间的共享锁完全
 *       兼容,不构成互斥。</li>
 * </ol>
 *
 * <p><b>剩余边界(如实声明,不假装已封闭)。</b>旧 <b>API-only</b> 实例既不取
 * 锁也不认识登记表,无法被任何它不知道的协议拦截;由发布流程的维护窗口
 * 覆盖(design §11"停止全部旧 Java/Python 执行进程")。
 *
 * <p><b>持有中丢失。</b>会话级锁随连接消失(数据库重启、后端被终止、网络
 * 中断)。与 {@code ExecutorLease} 同样的 fail-closed 语义:周期性用 pg_locks
 * 验证本进程后端仍持有共享锁,一旦判定丢失即永久闩锁——本进程停止认领任务
 * (经 {@code WorkerPollingGate}),健康检查明确失败;绝不静默重连重取
 * (重取成功不等于准入恢复,那会掩盖所有权交接)。
 *
 * <p><b>测试豁免。</b>{@code spec.agent.execution.mode-admission-enabled=false}
 * 时本组件整体不生效。仅限测试使用:缓存的单执行器测试上下文持有排他锁,
 * 与任务级测试上下文的共享锁在同一 JVM 内冲突;生产部署不得关闭。
 */
@Component
public class ExecutionModeAdmission implements DisposableBean,
        com.specagent.common.health.ExecutionModeAdmissionStatus {

    private static final Logger log = LoggerFactory.getLogger(ExecutionModeAdmission.class);

    /** 与 ExecutorLease 相同的数据库范围键:共享(任务级)与排他(单执行器)互斥。 */
    private static final long LOCK_KEY = RuntimeExecutionProtocol.EXECUTOR_LOCK_KEY;

    /** PostgreSQL 把单个 bigint 键拆成 (classid, objid) 两个 int4 存储。 */
    private static final long CLASS_ID = LOCK_KEY >>> 32;
    private static final long OBJECT_ID = LOCK_KEY & 0xFFFFFFFFL;

    private final DataSource dataSource;
    private final boolean perTask;
    private final boolean enabled;

    /** 准入持有的专属连接(共享锁与连接绑定;单执行器模式为 null)。 */
    private Connection lockConnection;
    private volatile int backendPid = -1;
    /** 永久丢失闩锁:一旦置为 true 绝不重置。 */
    private volatile boolean lost;
    private volatile String lostReason;
    private volatile String admittedMode;

    public ExecutionModeAdmission(
            DataSource dataSource,
            @Value("${spec.agent.execution.multi-worker-enabled:false}") boolean multiWorkerEnabled,
            @Value("${spec.agent.execution.mode-admission-enabled:true}") boolean admissionEnabled) {
        this.dataSource = dataSource;
        this.perTask = multiWorkerEnabled;
        this.enabled = admissionEnabled;
        if (!enabled) {
            log.warn("Execution mode admission is DISABLED by configuration; "
                    + "mixed-scheduler protection is not active (test-only setting)");
            return;
        }
        try {
            admit();
        } catch (SQLException ex) {
            releaseQuietly();
            throw new IllegalStateException(
                    "Execution mode admission failed on the database: " + ex.getMessage(), ex);
        } catch (RuntimeException ex) {
            // 拒绝启动的路径(模式不一致/共享锁被旧执行器持有)也必须释放
            // 已经打开的连接,绝不让失败启动泄漏会话。
            releaseQuietly();
            throw ex;
        }
    }

    /** 本实例被准入的调度模式(诊断/健康用)。 */
    public String admittedMode() {
        return admittedMode;
    }

    /**
     * 本进程的准入是否仍然有效。丢失是永久性的:返回 false 之后本实例不会再
     * 报告 owned(与 ExecutorLease 的 fail-closed 语义一致)。
     */
    public boolean owned() {
        if (!enabled) {
            return true;
        }
        if (!perTask) {
            // 单执行器模式:全库互斥由 ExecutorLease 的排他锁承担,本组件只做
            // 启动时的登记表校验,没有可丢失的会话锁。
            return true;
        }
        if (lost || lockConnection == null) {
            return false;
        }
        return !lost;
    }

    /** 准入是否已永久丢失(健康检查暴露)。 */
    public boolean isLost() {
        return lost;
    }

    /** 丢失原因;未丢失时为 null。 */
    public String getLostReason() {
        return lostReason;
    }

    private void admit() throws SQLException {
        lockConnection = dataSource.getConnection();
        backendPid = queryBackendPid();
        checkModeRegistration();
        if (!perTask) {
            log.info("Execution mode admission: database is admitted to SINGLE_EXECUTOR; "
                    + "the database-wide lease remains with ExecutorLease");
            return;
        }
        try (Statement statement = lockConnection.createStatement()) {
            var rs = statement.executeQuery(
                    "SELECT pg_try_advisory_lock_shared(" + LOCK_KEY + ")");
            rs.next();
            boolean acquired = rs.getBoolean(1);
            if (!acquired) {
                throw new IllegalStateException(
                        "Another (possibly legacy single-executor) executor owns this "
                                + "database (advisory lock " + LOCK_KEY + "). A per-task "
                                + "multi-worker fleet cannot run alongside it: stop the "
                                + "single-executor process or complete the maintenance-window "
                                + "mode switch first (design §11).");
            }
        }
        log.info("Execution mode admission: PER_TASK admitted (shared advisory lock {}, "
                + "backend pid {}); single-executor executors are now excluded from this database",
                LOCK_KEY, backendPid);
    }

    /**
     * 登记表校验:模式不一致立即拒绝启动。本方法不做任何"纠正"——模式切换
     * 是运维动作(维护窗口内显式改写该行),绝不是启动时的自动副作用。
     */
    private void checkModeRegistration() throws SQLException {
        String expected = perTask ? "PER_TASK" : "SINGLE_EXECUTOR";
        try (PreparedStatement statement = lockConnection.prepareStatement(
                "SELECT mode FROM runtime_execution_mode WHERE id = 1 FOR SHARE")) {
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException(
                            "runtime_execution_mode has no admission row; the schema "
                                    + "migration (V58) is missing or was tampered with");
                }
                String recorded = rs.getString("mode");
                this.admittedMode = recorded;
                if (!expected.equals(recorded)) {
                    throw new IllegalStateException(
                            "Execution mode admission refused: this database is admitted to "
                                    + recorded + ", but this instance runs " + expected
                                    + ". Mixed schedulers corrupt task ownership (design §11). "
                                    + "To switch modes, stop every instance, update "
                                    + "runtime_execution_mode, then start the new fleet.");
                }
            }
        }
        log.info("Execution mode admission: database mode '{}' matches this instance", expected);
    }

    /**
     * 周期验证本进程后端仍持有共享 advisory lock(仅任务级模式)。验证路径与
     * {@code ExecutorLease.verifyOwnership} 相同:pg_locks 存在性检查,绝不用
     * pg_try_advisory_lock 重取——重取成功意味着静默重新准入,那不是验证。
     */
    @Scheduled(fixedDelayString = "${spec.agent.execution.admission-verify-interval-ms:5000}")
    public void verifyStillAdmitted() {
        if (!enabled || !perTask || lost) {
            return;
        }
        Connection connection = this.lockConnection;
        if (connection == null) {
            latchLost("admission connection is gone");
            return;
        }
        boolean held;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM pg_locks WHERE locktype = 'advisory' "
                        + "AND classid = ? AND objid = ? AND pid = ?")) {
            statement.setLong(1, CLASS_ID);
            statement.setLong(2, OBJECT_ID);
            statement.setInt(3, backendPid);
            try (ResultSet rs = statement.executeQuery()) {
                held = rs.next();
            }
        } catch (SQLException ex) {
            latchLost("admission connection failed: " + ex.getMessage());
            return;
        }
        if (!held) {
            latchLost("shared advisory lock " + LOCK_KEY + " is no longer held by this "
                    + "process (backend pid " + backendPid + "); the admission was revoked "
                    + "or the connection died");
        }
    }

    private void latchLost(String reason) {
        if (lost) {
            return;
        }
        lost = true;
        lostReason = reason;
        log.error("Execution mode admission LOST: {}. This process stops claiming tasks; "
                + "in-flight ownership is unaffected (task tokens stay authoritative), "
                + "but no new work may be admitted until restart.", reason);
        releaseConnectionQuietly();
    }

    private int queryBackendPid() throws SQLException {
        try (Statement statement = lockConnection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void releaseQuietly() {
        releaseConnectionQuietly();
        backendPid = -1;
    }

    private void releaseConnectionQuietly() {
        try {
            if (lockConnection != null) {
                if (perTask) {
                    try (Statement statement=lockConnection.createStatement()) {
                        statement.execute("SELECT pg_advisory_unlock_shared("+LOCK_KEY+")");
                    }
                }
                lockConnection.close();
            }
        } catch (SQLException ignored) {
            // 释放路径的兜底;连接最终也会被回收。
        } finally {
            lockConnection = null;
        }
    }

    @Override
    public void destroy() {
        if (!enabled) {
            return;
        }
        releaseQuietly();
        log.info("Execution mode admission released (shared advisory lock {})", LOCK_KEY);
    }
}
