package com.specagent.agent.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * 文件名:ExecutorLease.java
 *
 * 用途:数据库范围的执行器所有权互斥(单实例交付的强制保障)。实现为
 * PostgreSQL 会话级 advisory lock:执行器进程在上下文初始化时,用一条
 * 专属连接 {@code pg_try_advisory_lock} 获取所有权,并在进程生命周期内
 * 持有。锁与连接绑定——
 * - 第二个执行器连接同一数据库时获取失败,启动以明确错误终止,绝不进入
 *   执行状态(孤儿恢复、任务认领都在所有权之后);
 * - 进程崩溃时连接随进程消失,锁立即由数据库释放,所有权可恢复;
 * - 正常关闭在 {@link #destroy()} 中显式释放。
 *
 * <strong>所有权与数据库实际状态对应(第二轮复核 R2-B 的闭合)。</strong>
 * 持有锁的数据库会话可能在进程仍然存活时消失——数据库重启、运维
 * {@code pg_terminate_backend}、网络中断都会让 PostgreSQL 释放会话锁。
 * 因此 {@link #owned()} 与 {@link #assertOwned()} 不再只读内存布尔值,
 * 而是对租约连接执行 pg_locks 存在性检查,确认"本进程的后端进程仍持有
 * 该 advisory lock":
 * - 租约连接断开或检查失败 → 所有权丢失;
 * - pg_locks 中查不到本后端持锁行 → 所有权丢失;
 * - 一旦判定丢失即永久锁定(fail closed):绝不静默重连、绝不重新竞争
 *   锁——重连成功不等于所有权恢复,重新获取还可能与新执行器的启动竞争。
 *   丢失后本进程停止认领任务,健康检查明确失败,在途任务交由下一次
 *   启动的孤儿恢复诚实终态化。
 *
 * <strong>在途写入保护(fencing token,第三轮复核 R3-A 的闭合)。</strong>
 * 单独的"先检查、后写入"不是原子协议:线程可以在检查通过之后暂停任意
 * 时间,期间所有权可能已经交接。因此写入保护不依赖任何一次性的检查,
 * 而是使用持久化的单调所有权代次(epoch):
 * - 本执行器在 advisory lock 之下获取代次(见 {@link #acquire()}):
 *   advisory lock 保证旧持有者已消失,代次递增在同一连接上原子完成;
 * - 接管必然意味着代次递增——新执行器的代次永远严格大于旧执行器;
 * - run 行的一切状态写入(检查点/终态/认领)由 {@link AgentRunRepository}
 *   以单条条件 UPDATE 完成,在同一语句内验证"全局代次仍等于本执行器
 *   代次"。因此旧执行器无论在检查后暂停多久,只要接管已提交,其后续
 *   写入立即落空(0 行)并由 {@link AgentRunService} 以
 *   {@link LeaseLostException} 拒绝——不依赖线程调度,不存在"检查通过
 *   后仍可写入"的时间窗口;
 * - 业务产物事务(Answer/补丁/规格快照/节点产物)把带所有权条件的
 *   检查点写入与业务写入放进同一数据库事务:条件不满足即整体回滚,
 *   旧执行器无法提交业务结果;
 * - 外部副作用(模型调用、能力调用)不在数据库事务之内,继续遵守既有
 *   的幂等键与所有权边界——数据库回滚不能撤销已经发出的外部请求。
 *
 * 回环绑定只限制网络可达范围,不构成实例互斥;本租约才是"同一时刻至多
 * 一个执行器"的产品边界的强制形式。关闭启动恢复不改变租约语义——关闭
 * 恢复只表示"不做孤儿收敛",绝不表示允许多执行器。
 *
 * 锁键为固定常量 {@link #LOCK_KEY},与业务数据无关联。
 */
@Component
@ConditionalOnProperty(name = "spec.agent.brain.worker.enabled", havingValue = "true")
public class ExecutorLease implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(ExecutorLease.class);

    /** 数据库范围固定的执行器租约键(任选常量,全库唯一语义)。 */
    public static final long LOCK_KEY = 736_251_904_113L;

    /**
     * owned() 的数据库验证结果缓存窗口。轮询 tick(默认 2s)之间的
     * owned() 读的是最近一次验证;assertOwned() 永远同步验证。
     */
    private static final long VERIFY_CACHE_NANOS = 500_000_000L; // 500ms

    private final DataSource dataSource;
    private final String lockDescription;

    /** PostgreSQL 把单个 bigint 键拆成 (classid, objid) 两个 int4 存储。 */
    private static final long CLASS_ID = LOCK_KEY >>> 32;
    private static final long OBJECT_ID = LOCK_KEY & 0xFFFFFFFFL;

    private Connection leaseConnection;
    private volatile int leasePid = -1;
    /**
     * 本执行器的所有权代次(fencing token)。在 advisory lock 之下获取并
     * 持久化;全局单行记录保证它严格单调,新执行器的代次永远更大。
     */
    private volatile long epoch;
    /** 已确认持有所有权的最近时刻(nanoTime);0 表示尚未验证。 */
    private volatile long lastVerifiedNanos = 0L;
    /** 永久丢失闩锁:一旦置为 true 绝不重置。 */
    private volatile boolean lost;
    private volatile String lostReason;

    public ExecutorLease(DataSource dataSource,
                         @Value("${spring.datasource.url:}") String datasourceUrl) {
        this.dataSource = dataSource;
        this.lockDescription = datasourceUrl == null || datasourceUrl.isBlank()
                ? "database" : datasourceUrl;
        try {
            acquire();
        } catch (SQLException ex) {
            releaseQuietly();
            throw new IllegalStateException(
                    "Executor lease could not be acquired on " + lockDescription
                            + ": " + ex.getMessage(), ex);
        }
    }

    private void acquire() throws SQLException {
        leaseConnection = dataSource.getConnection();
        leasePid = queryBackendPid();
        try (Statement statement = leaseConnection.createStatement()) {
            var rs = statement.executeQuery(
                    "SELECT pg_try_advisory_lock(" + LOCK_KEY + ")");
            rs.next();
            boolean acquired = rs.getBoolean(1);
            if (!acquired) {
                leaseConnection.close();
                leaseConnection = null;
                throw new IllegalStateException(
                        "Another executor already owns this database (advisory lock "
                                + LOCK_KEY + " on " + lockDescription + "). The product "
                                + "supports a single executor per database: stop the other "
                                + "process or point this instance at a different database.");
            }
        }
        lastVerifiedNanos = System.nanoTime();
        this.epoch = bumpOwnershipEpoch();
        log.info("Executor lease acquired on {} (backend pid {}, ownership epoch {})",
                lockDescription, leasePid, epoch);
    }

    /**
     * 在 advisory lock 之下原子地获取(并递增)全局所有权代次。advisory
     * lock 保证此刻没有其他执行器;单行 upsert 保证代次严格单调——接管
     * 必然意味着新代次,这正是 fencing token 的来源。
     */
    private long bumpOwnershipEpoch() throws SQLException {
        try (PreparedStatement statement = leaseConnection.prepareStatement(
                "INSERT INTO executor_ownership (id, epoch) VALUES (1, 1) "
                        + "ON CONFLICT (id) DO UPDATE "
                        + "SET epoch = executor_ownership.epoch + 1, updated_at = now() "
                        + "RETURNING epoch")) {
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** 本执行器的所有权代次(fencing token);run 写入的所有权条件携带它。 */
    public long epoch() {
        return epoch;
    }

    /**
     * 所有权是否仍在本进程手中。内存判定 + 最近一次数据库验证的缓存读;
     * 缓存过期时执行一次 pg_locks 验证。丢失是永久的:返回 false 之后
     * 本实例不会再报告 owned。
     */
    public boolean owned() {
        if (lost || leaseConnection == null) {
            return false;
        }
        long verified = lastVerifiedNanos;
        if (verified != 0L && System.nanoTime() - verified < VERIFY_CACHE_NANOS) {
            return true;
        }
        try {
            return verifyOwnership();
        } catch (SQLException ex) {
            latchLost("lease connection failed: " + ex.getMessage());
            return false;
        }
    }

    /**
     * 恢复、认领、持久化检查点与业务写入前的强校验:对数据库同步验证
     * 所有权,丢失立即抛出 {@link LeaseLostException}(调用方的写入路径
     * 据此回滚,绝不提交新执行器接管后的业务结果)。
     */
    public void assertOwned() {
        if (lost) {
            throw new LeaseLostException(lostReason);
        }
        try {
            if (!verifyOwnership()) {
                throw new LeaseLostException(lostReason);
            }
        } catch (SQLException ex) {
            latchLost("lease connection failed: " + ex.getMessage());
            throw new LeaseLostException(lostReason);
        }
    }

    /** 是否已永久失去所有权(健康检查暴露)。 */
    public boolean isLost() {
        return lost;
    }

    /** 失去所有权的原因;未丢失时为 null。 */
    public String getLostReason() {
        return lostReason;
    }

    /** 租约连接的 PostgreSQL 后端 pid(诊断用);未持有时为 -1。 */
    public int getLeasePid() {
        return leasePid;
    }

    /**
     * 对租约连接执行 pg_locks 存在性检查:确认"本进程的后端进程仍持有
     * 会话级 advisory lock"。绝不用 pg_try_advisory_lock 重取——重取成功
     * 意味着静默重新获取所有权,那不是验证而是自愈,会掩盖所有权交接。
     */
    private boolean verifyOwnership() throws SQLException {
        Connection connection = this.leaseConnection;
        if (connection == null) {
            return false;
        }
        boolean held;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM pg_locks WHERE locktype = 'advisory' "
                        + "AND classid = ? AND objid = ? AND pid = ?")) {
            statement.setLong(1, CLASS_ID);
            statement.setLong(2, OBJECT_ID);
            statement.setInt(3, leasePid);
            try (ResultSet rs = statement.executeQuery()) {
                held = rs.next();
            }
        }
        if (!held) {
            latchLost("advisory lock " + LOCK_KEY + " is no longer held by this "
                    + "process (backend pid " + leasePid + "); another executor may "
                    + "have taken over");
            return false;
        }
        lastVerifiedNanos = System.nanoTime();
        return true;
    }

    private void latchLost(String reason) {
        if (lost) {
            return;
        }
        lost = true;
        lostReason = reason;
        log.error("Executor lease LOST on {}: {}. This process stops claiming runs; "
                + "in-flight writes are refused until restart.", lockDescription, reason);
        releaseConnectionQuietly();
    }

    private int queryBackendPid() throws SQLException {
        try (Statement statement = leaseConnection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT pg_backend_pid()")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private void releaseQuietly() {
        releaseConnectionQuietly();
        leasePid = -1;
        lastVerifiedNanos = 0L;
    }

    private void releaseConnectionQuietly() {
        try {
            if (leaseConnection != null) {
                leaseConnection.close();
            }
        } catch (SQLException ignored) {
            // 释放路径的兜底;连接最终也会被回收。
        } finally {
            leaseConnection = null;
        }
    }

    @Override
    public void destroy() throws Exception {
        if (!lost && leaseConnection != null) {
            lastVerifiedNanos = 0L;
            try (Statement statement = leaseConnection.createStatement()) {
                statement.execute("SELECT pg_advisory_unlock(" + LOCK_KEY + ")");
            } finally {
                leaseConnection.close();
                leaseConnection = null;
            }
            log.info("Executor lease released on {}", lockDescription);
        }
    }

    /** 所有权已丢失后的一切所有权敏感动作都会收到本异常。 */
    public static class LeaseLostException extends IllegalStateException {
        public LeaseLostException(String reason) {
            super("Executor lease is lost: " + reason
                    + "; refusing to touch agent runs");
        }
    }
}
