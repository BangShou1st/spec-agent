package com.specagent.runtime.execution;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:TaskWriteGuard.java
 *
 * 用途:受保护写入的统一护栏(design §6.3),替换旧的全局单执行器
 * {@code ExecutionFence}。
 *
 * <p><b>为什么必须锁行而不是比较代次。</b>旧实现用"语句内快照子查询比较
 * 全局代次",被真实数据库反例证明不成立:UPDATE 在 PostgreSQL 内等待目标行
 * 锁期间,另一个连接仍可提交代次递增,旧 UPDATE 恢复后依旧按语句开始时的
 * 快照通过条件并提交。本护栏改为<b>对 runtime_tasks 的同一行取排他锁</b>:
 * 接管事务也锁同一行,于是只存在两种可接受顺序——旧事务先提交(接管等待它
 * 结束),或接管先提交(旧事务取锁后读到新租约,验证失败、整体回滚)。
 * 禁止的结果(新 token 已提交而旧事务仍成功提交)不可能出现。
 *
 * <p><b>必须作为业务事务的第一条数据库语句调用</b>,先于一切业务行锁,以保持
 * 统一锁序:task 行 → 容量槽 → scope 行 → 业务 Run → 业务行。事务保持短,
 * 并有锁等待/语句超时。
 *
 * <p>token 的每一项都在锁内重新核实:state=LEASED、worker/epoch/lease 三项
 * 匹配、租约未过期、未取消、未超过绝对 deadline。任何一项不满足即抛出
 * {@link TaskOwnershipLostException},调用方事务整体回滚——业务产物与
 * checkpoint 在同一事务,因此迟到写入不会留下任何痕迹。
 */
@Component
public class TaskWriteGuard {

    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<GuardedTask> rowMapper;
    private final RuntimeTaskAuthorization authorization;
    private final RuntimeScopeMutex scopeMutex;

    public TaskWriteGuard(NamedParameterJdbcTemplate jdbc, RuntimeTaskAuthorization authorization) {
        this(jdbc, authorization, new RuntimeScopeMutex(jdbc));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public TaskWriteGuard(NamedParameterJdbcTemplate jdbc, RuntimeTaskAuthorization authorization, RuntimeScopeMutex scopeMutex) {
        this.jdbc = jdbc;
        this.authorization=authorization;
        this.scopeMutex=scopeMutex;
        this.rowMapper = (rs, rowNum) -> new GuardedTask(
                rs.getObject("id", UUID.class),
                rs.getString("state"),
                rs.getObject("worker_id", UUID.class),
                rs.getLong("execution_epoch"),
                rs.getObject("lease_id", UUID.class),
                instant(rs.getTimestamp("lease_until")),
                instant(rs.getTimestamp("deadline_at")),
                instant(rs.getTimestamp("cancel_requested_at")));
    }

    private ExecutionHeartbeatRegistry heartbeatRegistry;

    /**
     * 心跳失权信号(design §6.4)。本进程已判定失去所有权时,受保护写入立即
     * 拒绝,不必等数据库往返——但它<b>只是提前拒绝</b>:数据库行锁与条件校验
     * 仍是提交的最终权威(以下 {@code FOR UPDATE} + token 校验不变)。
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHeartbeatRegistry(ExecutionHeartbeatRegistry heartbeatRegistry) {
        this.heartbeatRegistry = heartbeatRegistry;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private record GuardedTask(UUID id,
                               String state,
                               UUID workerId,
                               long epoch,
                               UUID leaseId,
                               Instant leaseUntil,
                               Instant deadlineAt,
                               Instant cancelRequestedAt) {
    }

    /**
     * 在当前事务内锁住任务行并核实所有权,返回通过验证的 token。
     *
     * @throws TaskOwnershipLostException 任务不存在、不再 LEASED、token 不匹配、
     *                                    租约过期、已取消或已过绝对截止时间
     */
    public ExecutionToken lockOwnedTask(ExecutionToken token) {
        // 本进程已知失去所有权(心跳续租失败)时立即拒绝,不必等数据库往返:
        // 数据库可能短暂不可达,而租约会话已在等待期间被其他线程判失。
        // 数据库护栏(以下 FOR UPDATE + token/租约/取消/deadline 校验)仍是最终权威。
        if (heartbeatRegistry != null && heartbeatRegistry.isOwnershipLost(token)) {
            throw new TaskOwnershipLostException(
                    "heartbeat ownership lost for " + token.describe()
                            + "; refusing protected write");
        }
        GuardedTask task = jdbc.query("""
                SELECT id, state, worker_id, execution_epoch, lease_id, lease_until,
                       deadline_at, cancel_requested_at
                FROM runtime_tasks WHERE id = :taskId FOR UPDATE
                """, Map.of("taskId", token.taskId()), rowMapper).stream().findFirst()
                .orElseThrow(() -> new TaskOwnershipLostException(
                        "runtime task " + token.taskId() + " no longer exists"));

        if (!"LEASED".equals(task.state())) {
            throw new TaskOwnershipLostException(
                    "runtime task " + token.taskId() + " is " + task.state()
                            + ", not LEASED; refusing write from " + token.describe());
        }
        if (!token.workerId().equals(task.workerId())
                || token.epoch() != task.epoch()
                || !token.leaseId().equals(task.leaseId())) {
            throw new TaskOwnershipLostException(
                    "ownership mismatch on runtime task " + token.taskId()
                            + " (expected epoch " + token.epoch() + "/lease " + token.leaseId()
                            + ", stored epoch " + task.epoch() + "/lease " + task.leaseId()
                            + "); another worker has taken over");
        }
        if (task.cancelRequestedAt() != null) {
            throw new TaskOwnershipLostException(
                    "runtime task " + token.taskId() + " was cancelled; refusing write");
        }
        // 租约与 deadline 用实时数据库时钟判定(clock_timestamp,多实例的本地
        // 时钟不可信)。绝不能用 now():它是事务开始时间——本事务可能先于校验
        // 在 FOR UPDATE 上等待过 task 行锁(接管事务持锁提交),等待期间租约或
        // deadline 跨过到期点后,now() 仍是旧值,过期租约会被放行。与
        // RuntimeTaskRepository.renewLease 相同的教训与修法:独立的第二条语句
        // 保证时钟在取得行锁之后才求值。
        Boolean leaseValid = jdbc.queryForObject(
                "SELECT lease_until > clock_timestamp() AND (deadline_at IS NULL OR deadline_at > clock_timestamp()) "
                        + "FROM runtime_tasks WHERE id = :taskId",
                Map.of("taskId", token.taskId()), Boolean.class);
        if (!Boolean.TRUE.equals(leaseValid)) {
            throw new TaskOwnershipLostException(
                    "lease or absolute deadline expired on runtime task " + token.taskId()
                            + "; refusing write from " + token.describe());
        }
        var scopes = jdbc.queryForList("SELECT scope_key FROM runtime_task_scopes WHERE task_id=:id ORDER BY scope_key",
                Map.of("id", token.taskId()), String.class);
        if (scopes.isEmpty() || !scopeMutex.tryLock(scopes))
            throw new TaskOwnershipLostException("Execution scope unavailable; refusing protected write");
        Integer bound = jdbc.queryForObject("""
                SELECT count(*) FROM runtime_scope_owners WHERE task_id=:id AND scope_key IN (:scopes)
                    AND execution_epoch=:epoch AND lease_id=:lease AND worker_id=:worker
                """, Map.of("id", token.taskId(), "scopes", scopes, "epoch", token.epoch(),
                        "lease", token.leaseId(), "worker", token.workerId()), Integer.class);
        if (bound == null || bound != scopes.size())
            throw new TaskOwnershipLostException("Execution scope ownership binding is missing; refusing protected write");
        authorization.require(token);
        return token;
    }

    /**
     * 只核实、不抛异常的探测版本,供健康检查、诊断与"是否可以继续发起新步骤"
     * 的判定使用。
     */
    public boolean stillOwned(ExecutionToken token) {
        try {
            lockOwnedTask(token);
            return true;
        } catch (TaskOwnershipLostException expected) {
            return false;
        }
    }

    /**
     * 无 token 的读取视图:任务当前是否处于"有存活 owner"的状态。
     * 不用于写入决策。
     */
    public boolean hasLiveOwner(UUID taskId) {
        Boolean live = jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM runtime_tasks
                WHERE id = :taskId AND state = 'LEASED' AND lease_until > clock_timestamp()
                  AND (deadline_at IS NULL OR deadline_at > clock_timestamp())
                  AND cancel_requested_at IS NULL)
                """, Map.of("taskId", taskId), Boolean.class);
        return Boolean.TRUE.equals(live);
    }

    /** 业务 Run 当前的任务(读取视图;写入决策一律走 lockOwnedTask)。 */
    public Optional<RuntimeTask> currentTaskOfWorkspaceRun(UUID runId) {
        return find("workspace_run_id", runId);
    }

    public Optional<RuntimeTask> currentTaskOfGaRun(UUID runId) {
        return find("ga_run_id", runId);
    }

    private Optional<RuntimeTask> find(String column, UUID runId) {
        // 列名来自本类的私有白名单常量,绝不来自外部输入。
        String sql = "SELECT * FROM runtime_tasks WHERE " + column + " = :runId";
        return jdbc.query(sql, Map.of("runId", runId), (rs, rowNum) -> new RuntimeTask(
                rs.getObject("id", UUID.class),
                RuntimeTaskFamily.fromCode(rs.getString("family")),
                rs.getObject("workspace_run_id", UUID.class),
                rs.getObject("ga_run_id", UUID.class),
                rs.getString("task_type"),
                RuntimeTaskState.fromCode(rs.getString("state")),
                rs.getObject("worker_id", UUID.class),
                rs.getLong("execution_epoch"),
                rs.getObject("lease_id", UUID.class),
                instant(rs.getTimestamp("lease_until")),
                instant(rs.getTimestamp("heartbeat_at")),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts"),
                instant(rs.getTimestamp("available_at")),
                instant(rs.getTimestamp("created_at")),
                instant(rs.getTimestamp("deadline_at")),
                instant(rs.getTimestamp("cancel_requested_at")),
                rs.getString("input_ref"),
                rs.getString("input_version"),
                rs.getString("last_error_code"),
                rs.getString("recovery_reason"),
                rs.getInt("protocol_version"),
                new TaskActor(
                        rs.getObject("actor_user_id", UUID.class),
                        rs.getObject("actor_org_id", UUID.class),
                        com.specagent.access.ActorSource.fromCode(rs.getString("actor_source"))))).stream().findFirst();
    }
}
