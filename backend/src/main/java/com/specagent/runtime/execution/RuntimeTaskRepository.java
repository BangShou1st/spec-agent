package com.specagent.runtime.execution;

import com.specagent.access.ActorSource;
import com.specagent.common.Maps;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:RuntimeTaskRepository.java
 *
 * 用途:公共调度表 runtime_tasks 及其范围/容量/attempt 副表的持久化出口
 * (docs/v2/PHASE1_MULTI_WORKER_EXECUTION_DESIGN.md §5、§6)。
 *
 * <p>事务形态:本仓储<b>不</b>自带 {@code @Transactional}。认领、心跳、
 * 终态化都由调用方(调度服务)在单个事务里按固定锁序组合:
 * task 行 → 容量槽(按键排序) → scope 行(按键排序) → 业务 Run → 业务行。
 * 仓储只提供语句级原语。
 *
 * <p>时间一律使用数据库时钟({@code now()} / {@code clock_timestamp()}):
 * 多实例的本地时钟不可信,租约过期判定必须与认领写入同源。
 */
@Repository
public class RuntimeTaskRepository {
    public void lockOwnedResources(UUID taskId) {
        jdbc.queryForList("""
                SELECT slot_key FROM runtime_capacity_slots WHERE slot_key IN
                    (SELECT slot_key FROM runtime_task_capacity WHERE task_id=:id)
                ORDER BY slot_key FOR UPDATE
                """,Maps.of("id",taskId));
        jdbc.queryForList("SELECT scope_key FROM runtime_scope_owners WHERE task_id=:id ORDER BY scope_key FOR UPDATE",
                Maps.of("id",taskId));
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final RuntimeScopeMutex scopeMutex;
    private final RowMapper<RuntimeTask> rowMapper;

    public RuntimeTaskRepository(NamedParameterJdbcTemplate jdbc) {
        this(jdbc, new RuntimeScopeMutex(jdbc));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RuntimeTaskRepository(NamedParameterJdbcTemplate jdbc, RuntimeScopeMutex scopeMutex) {
        this.jdbc = jdbc;
        this.scopeMutex = scopeMutex;
        this.rowMapper = (rs, rowNum) -> new RuntimeTask(
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
                        ActorSource.fromCode(rs.getString("actor_source"))));
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    /**
     * 在当前事务内插入任务行。入队与业务 Run 必须在同一事务(design §6.1):
     * 事务提交后立即崩溃也不能丢任务。每个业务 Run 至多一个任务,由唯一
     * 索引兜底。
     */
    public void insert(UUID taskId,
                       RuntimeTaskFamily family,
                       UUID workspaceRunId,
                       UUID gaRunId,
                       String taskType,
                       String inputRef,
                       String inputVersion,
                       int maxAttempts,
                       int protocolVersion,
                       TaskActor actor,
                       Collection<String> scopes) {
        jdbc.update("""
                INSERT INTO runtime_tasks (id, family, workspace_run_id, ga_run_id, task_type,
                    state, attempt_count, max_attempts, input_ref, input_version,
                    protocol_version, actor_user_id, actor_org_id, actor_source,
                    available_at, created_at)
                VALUES (:id, :family, :workspaceRunId, :gaRunId, :taskType,
                    'QUEUED', 0, :maxAttempts, :inputRef, :inputVersion,
                    :protocolVersion, :actorUserId, :actorOrgId, :actorSource,
                    now(), now())
                """, Maps.of(
                "id", taskId,
                "family", family.code(),
                "workspaceRunId", workspaceRunId,
                "gaRunId", gaRunId,
                "taskType", taskType,
                "maxAttempts", maxAttempts,
                "inputRef", inputRef,
                "inputVersion", inputVersion,
                "protocolVersion", protocolVersion,
                "actorUserId", actor.userId(),
                "actorOrgId", actor.orgId(),
                "actorSource", actor.source().name()));
        for (String scope : scopes) {
            jdbc.update("INSERT INTO runtime_task_scopes (task_id, scope_key) "
                    + "VALUES (:taskId, :scopeKey)", Maps.of("taskId", taskId, "scopeKey", scope));
        }
    }

    /** 同一事务内的容量槽占用绑定(认领时写)。 */
    public void bindCapacity(UUID taskId, String slotKey) {
        jdbc.update("INSERT INTO runtime_task_capacity (task_id, slot_key) "
                + "VALUES (:taskId, :slotKey) ON CONFLICT DO NOTHING",
                Maps.of("taskId", taskId, "slotKey", slotKey));
    }

    public Optional<RuntimeTask> findById(UUID taskId) {
        return jdbc.query("SELECT * FROM runtime_tasks WHERE id = :id",
                Maps.of("id", taskId), rowMapper).stream().findFirst();
    }

    /** 按业务 Run 反查任务(受保护写入与终态读取使用)。 */
    public Optional<RuntimeTask> findByWorkspaceRunId(UUID runId) {
        return jdbc.query("SELECT * FROM runtime_tasks WHERE workspace_run_id = :runId",
                Maps.of("runId", runId), rowMapper).stream().findFirst();
    }

    /** 用户接受提案时先锁原始任务，再取得授权范围和业务 Run 锁。 */
    public Optional<RuntimeTask> lockWorkspaceRunTask(UUID runId) {
        return jdbc.query("SELECT * FROM runtime_tasks WHERE workspace_run_id=:runId FOR UPDATE",
                Maps.of("runId", runId), rowMapper).stream().findFirst();
    }

    public Optional<RuntimeTask> findByGaRunId(UUID runId) {
        return jdbc.query("SELECT * FROM runtime_tasks WHERE ga_run_id = :runId",
                Maps.of("runId", runId), rowMapper).stream().findFirst();
    }

    /**
     * 锁住任务行并返回当前事实。这是受保护写入、接管、取消与终态化的
     * 统一入口锁(design §6.3):接管也锁同一行,因此"旧事务先提交"与
     * "接管先提交"之间不存在第三条路径。
     */
    public Optional<RuntimeTask> lockById(UUID taskId) {
        return jdbc.query("SELECT * FROM runtime_tasks WHERE id = :id FOR UPDATE",
                Maps.of("id", taskId), rowMapper).stream().findFirst();
    }

    /** 取该任务声明的范围键,按键升序以固定锁序。 */
    public List<String> scopesOf(UUID taskId) {
        return jdbc.queryForList("SELECT scope_key FROM runtime_task_scopes "
                + "WHERE task_id = :taskId ORDER BY scope_key",
                Maps.of("taskId", taskId), String.class);
    }

    /**
     * 按固定顺序(slot_key 升序)锁住本任务需要的容量槽并尝试占用。
     *
     * <p>返回 false 表示容量不足:调用方必须回滚候选认领并继续扫描其他
     * 候选,绝不等待(design §6.2)。
     *
     * <p>用 {@code queryForList} 而不是 {@code queryForObject}:条件不满足时
     * UPDATE 影响 0 行,后者会抛 {@code EmptyResultDataAccessException} 而不是
     * 返回 null——那会让"容量已满"这一正常调度事实变成异常。
     */
    public boolean tryAcquireCapacity(List<String> slotKeys) {
        for (String slotKey : slotKeys) {
            List<Integer> acquired = jdbc.queryForList("""
                    UPDATE runtime_capacity_slots
                    SET used = used + 1, updated_at = now()
                    WHERE slot_key = :slotKey AND used < capacity
                    RETURNING 1
                    """, Maps.of("slotKey", slotKey), Integer.class);
            if (acquired.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** 按槽键直接释放容量(候选尝试失败、但尚未绑定到任务时使用)。 */
    public void releaseCapacityKeys(List<String> slotKeys) {
        for (String slotKey : slotKeys) {
            jdbc.update("UPDATE runtime_capacity_slots SET used = GREATEST(used - 1, 0), "
                    + "updated_at = now() WHERE slot_key = :slotKey", Maps.of("slotKey", slotKey));
        }
    }

    /** 释放本任务占用的全部容量槽(终态化/接管/取消时调用)。 */
    public void releaseCapacity(UUID taskId) {
        List<String> slots = jdbc.queryForList("SELECT slot_key FROM runtime_task_capacity "
                + "WHERE task_id = :taskId ORDER BY slot_key", Maps.of("taskId", taskId), String.class);
        jdbc.update("DELETE FROM runtime_task_capacity WHERE task_id = :taskId",
                Maps.of("taskId", taskId));
        for (String slotKey : slots) {
            jdbc.update("UPDATE runtime_capacity_slots SET used = GREATEST(used - 1, 0), "
                    + "updated_at = now() WHERE slot_key = :slotKey", Maps.of("slotKey", slotKey));
        }
    }

    /**
     * 容量计数自愈(design §5.4)。
     *
     * <p>{@code runtime_capacity_slots.used} 是原子认领所需的冗余计数,但它可能
     * 与事实漂移:业务行被级联删除时 {@code runtime_task_capacity} 会随之消失,
     * 而计数不会被递减;进程在"占槽"与"写绑定行"之间崩溃也会留下半个占用。漂移
     * 的后果是执行槽被永久吞噬——系统逐渐不再认领任何任务。
     *
     * <p>因此过期扫描的每个周期都重算一次:计数<b>以占用绑定行为唯一事实</b>,
     * 只修正有偏差的行。这是幂等的自愈,不是业务动作。
     *
     * @return 被修正的槽数
     */
    public int reconcileCapacity() {
        // 锁序(设计不变量):一切 runtime_capacity_slots 行锁都按 slot_key 升序
        // 取得(认领 tryAcquireCapacity=GLOBAL→family、终态 releaseCapacity=
        // ORDER BY slot_key)。本方法若用单条多行 UPDATE,行锁顺序取决于
        // 执行计划的扫描顺序,可能与认领事务的取锁顺序相反,构成死锁对
        // (P1-E 双实例验收真实复现:认领 G→W vs 对账 W→G)。因此必须逐槽、
        // 按升序执行,与全库锁序一致。
        int corrected = 0;
        for (String slotKey : List.of(
                RuntimeExecutionProtocol.SLOT_GLOBAL,
                RuntimeExecutionProtocol.SLOT_GLOBAL_ASSISTANT,
                RuntimeExecutionProtocol.SLOT_WORKSPACE)) {
            corrected += jdbc.update("""
                    UPDATE runtime_capacity_slots s
                    SET used = actual.total, updated_at = now()
                    FROM (
                        SELECT COUNT(*) AS total
                        FROM runtime_task_capacity WHERE slot_key = :slotKey
                    ) actual
                    WHERE s.slot_key = :slotKey AND s.used <> actual.total
                    """, Maps.of("slotKey", slotKey));
            corrected += jdbc.update("""
                    UPDATE runtime_capacity_slots
                    SET used = 0, updated_at = now()
                    WHERE slot_key = :slotKey AND used <> 0
                      AND NOT EXISTS (SELECT 1 FROM runtime_task_capacity c
                                      WHERE c.slot_key = :slotKey)
                    """, Maps.of("slotKey", slotKey));
        }
        return corrected;
    }

    /**
     * 按固定顺序(scope_key 升序)尝试取得全部范围所有权。
     *
     * <p>范围被存活占用者持有 → 返回 false,调用方回滚候选并扫描其他候选,
     * 绝不等待它跑完(design §6.2 第 3 步)。
     */
    public boolean tryAcquireScopes(UUID taskId, ExecutionToken token, List<String> scopeKeys) {
        if (!scopeMutex.tryLock(scopeKeys)) return false;
        for (String scopeKey : scopeKeys.stream().distinct().sorted().toList()) {
            int inserted = jdbc.update("""
                    INSERT INTO runtime_scope_owners (scope_key, task_id, execution_epoch, lease_id, worker_id)
                    VALUES (:scopeKey, :taskId, :epoch, :leaseId, :workerId)
                    ON CONFLICT (scope_key) DO NOTHING
                    """, Maps.of(
                    "scopeKey", scopeKey,
                    "taskId", taskId,
                    "epoch", token.epoch(),
                    "leaseId", token.leaseId(),
                    "workerId", token.workerId()));
            if (inserted != 1) {
                return false;
            }
        }
        return true;
    }

    /** 释放本任务持有的范围所有权(终态化/接管/取消时调用)。 */
    public void releaseScopes(UUID taskId) {
        jdbc.update("DELETE FROM runtime_scope_owners WHERE task_id = :taskId",
                Maps.of("taskId", taskId));
    }

    /** 撤销某任务的旧范围所有权(接管时先撤销再转移)。 */
    public void releaseScopesOf(UUID taskId) {
        releaseScopes(taskId);
    }

    /**
     * 候选认领:用 {@code FOR UPDATE SKIP LOCKED} 选出下一个候选,排序
     * {@code (available_at, created_at, id)},只覆盖 QUEUED 且协议版本兼容、
     * 未取消、未过总截止时间的行(design §6.2 第 2 步)。
     *
     * <p>每次扫描有界:调用方传入 limit,避免同一个繁忙项目挡住其他项目。
     * 本方法只读取候选,不改状态——容量与范围都要先拿到。
     */
    public List<RuntimeTask> scanQueuedCandidates(RuntimeTaskFamily family, int limit) {
        return jdbc.query("""
                SELECT * FROM runtime_tasks
                WHERE state = 'QUEUED'
                  AND family = :family
                  AND protocol_version = :protocolVersion
                  AND available_at <= now()
                  AND cancel_requested_at IS NULL
                  AND (deadline_at IS NULL OR deadline_at > clock_timestamp())
                ORDER BY available_at, created_at, id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """, Maps.of(
                "family", family.code(),
                "protocolVersion", RuntimeExecutionProtocol.VERSION,
                "limit", limit), rowMapper);
    }

    /**
     * 选取<b>一个</b>候选并加行锁。每次只取一条,使"某个繁忙项目的候选
     * 占用范围失败"不会连带锁住其他项目的候选;排除已尝试过的 id,避免
     * 同一轮扫描反复选中同一个范围冲突的任务。
     *
     * @param taskTypes 允许的 task_type;空集表示不限
     */
    public Optional<RuntimeTask> lockNextQueuedCandidate(RuntimeTaskFamily family,
                                                         Collection<String> taskTypes,
                                                         Collection<UUID> excluded) {
        return jdbc.query("""
                SELECT * FROM runtime_tasks
                WHERE state = 'QUEUED'
                  AND family = :family
                  AND protocol_version = :protocolVersion
                  AND available_at <= now()
                  AND cancel_requested_at IS NULL
                  AND (deadline_at IS NULL OR deadline_at > clock_timestamp())
                  AND (cardinality(:taskTypes) = 0 OR task_type = ANY(:taskTypes))
                  AND NOT (id = ANY(:excluded))
                ORDER BY available_at, created_at, id
                LIMIT 1
                FOR UPDATE SKIP LOCKED
                """, Maps.of(
                "family", family.code(),
                "protocolVersion", RuntimeExecutionProtocol.VERSION,
                "taskTypes", taskTypes.isEmpty() ? EMPTY_STRINGS : taskTypes.toArray(new String[0]),
                "excluded", excluded.isEmpty() ? EMPTY_UUIDS : excluded.toArray(new UUID[0])),
                rowMapper).stream().findFirst();
    }

    private static final UUID[] EMPTY_UUIDS = new UUID[0];
    private static final String[] EMPTY_STRINGS = new String[0];

    /** 运行该任务的业务 Run 所属项目(画布族范围解析用)。 */
    public Optional<UUID> projectIdOfWorkspaceRun(UUID runId) {
        return jdbc.queryForList("SELECT project_id FROM agent_runs WHERE id = :runId",
                Maps.of("runId", runId), UUID.class).stream().findFirst();
    }

    /** 运行该任务的业务 Run 所属助手会话(GA 族范围解析用)。 */
    public Optional<UUID> threadIdOfGaRun(UUID runId) {
        return jdbc.queryForList("SELECT thread_id FROM global_assistant_runs WHERE id = :runId",
                Maps.of("runId", runId), UUID.class).stream().findFirst();
    }

    /** 幂等补建任务行(迁移窗口中的遗留 Run 在首次认领时补齐)。 */
    public int insertIfAbsent(UUID taskId, RuntimeTaskFamily family, UUID workspaceRunId,
                              UUID gaRunId, String taskType, int maxAttempts, int protocolVersion,
                              TaskActor actor) {
        return jdbc.update("""
                INSERT INTO runtime_tasks (id, family, workspace_run_id, ga_run_id, task_type,
                    state, attempt_count, max_attempts, protocol_version,
                    actor_user_id, actor_org_id, actor_source, available_at, created_at)
                VALUES (:id, :family, :workspaceRunId, :gaRunId, :taskType,
                    'QUEUED', 0, :maxAttempts, :protocolVersion,
                    :actorUserId, :actorOrgId, :actorSource, now(), now())
                ON CONFLICT DO NOTHING
                """, Maps.of(
                "id", taskId,
                "family", family.code(),
                "workspaceRunId", workspaceRunId,
                "gaRunId", gaRunId,
                "taskType", taskType,
                "maxAttempts", maxAttempts,
                "protocolVersion", protocolVersion,
                "actorUserId", actor.userId(),
                "actorOrgId", actor.orgId(),
                "actorSource", actor.source().name()));
    }

    /** 幂等声明任务范围(重复声明忽略)。范围只能由 Java 生成。 */
    public void declareScope(UUID taskId, String scopeKey) {
        jdbc.update("INSERT INTO runtime_task_scopes (task_id, scope_key) "
                + "VALUES (:taskId, :scopeKey) ON CONFLICT DO NOTHING",
                Maps.of("taskId", taskId, "scopeKey", scopeKey));
    }

    /** 当前排队任务总数(入队容量检查用)。 */
    public int queuedCount() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM runtime_tasks WHERE state IN ('QUEUED', 'LEASED', 'RECOVERING')",
                Map.of(), Integer.class);
        return count == null ? 0 : count;
    }

    /**
     * 入队容量检查的数据库级串行化点(design §9)。入队事务必须<b>先</b>调用
     * 本方法取得该族配额行的排他锁,再做计数与插入——否则并发入队会各自
     * COUNT 到相同的低于上限值并无锁插入,上限形同虚设。
     *
     * <p>这是叶子锁:只被入队路径取得,认领/终态/恢复一律不碰,因此不参与
     * task → 容量槽 → scope → 业务 Run 的锁序。
     */
    public void lockQueueQuota(RuntimeTaskFamily family) {
        jdbc.queryForList("SELECT family FROM runtime_queue_quota WHERE family = :family FOR UPDATE",
                Maps.of("family", family.code()));
    }

    /** 指定族当前在队(含在途)的任务数,已持有配额锁时调用。 */
    public int inFlightCountForFamily(RuntimeTaskFamily family) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM runtime_tasks
                WHERE family = :family AND state IN ('QUEUED', 'LEASED', 'RECOVERING')
                """, Maps.of("family", family.code()), Integer.class);
        return count == null ? 0 : count;
    }

    /** 指定范围内的排队任务数(单范围容量上限检查用)。 */
    public int queuedCountForScope(String scopeKey) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM runtime_tasks t
                JOIN runtime_task_scopes s ON s.task_id = t.id
                WHERE s.scope_key = :scopeKey
                  AND t.state IN ('QUEUED', 'LEASED', 'RECOVERING')
                """, Maps.of("scopeKey", scopeKey), Integer.class);
        return count == null ? 0 : count;
    }

    /** 确认任务声明的范围是否与业务事实一致(fail-closed,不猜测)。 */
    public boolean hasScope(UUID taskId, String scopeKey) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM runtime_task_scopes "
                        + "WHERE task_id = :taskId AND scope_key = :scopeKey",
                Maps.of("taskId", taskId, "scopeKey", scopeKey), Integer.class);
        return count != null && count > 0;
    }

    /**
     * 把候选推进为 LEASED:写 worker/epoch/lease/租约、递增执行代次与 attempt,
     * 并固定首次执行的绝对 deadline(design §6.2 第 4 步、§5.1)。
     *
     * <p>代次由数据库在 UPDATE 内递增(而不是由 Java 读后写),因此并发认领
     * 不可能算出同一个代次。条件里同时校验"仍是 QUEUED 且未取消":候选扫描
     * 与推进之间可能发生取消。返回空表示候选失效,调用方回滚。
     *
     * <p>deadline 用 {@code COALESCE} 只在首次执行时固定;恢复或重排绝不重置
     * 它(恢复不能重新得到完整预算)。
     */
    public Optional<RuntimeTask> lease(UUID taskId,
                                       ExecutionToken token,
                                       int leaseSeconds,
                                       int timeoutSeconds) {
        return jdbc.query("""
                UPDATE runtime_tasks
                SET state = 'LEASED',
                    worker_id = :workerId,
                    execution_epoch = execution_epoch + 1,
                    lease_id = :leaseId,
                    lease_until = now() + make_interval(secs => :leaseSeconds),
                    heartbeat_at = now(),
                    attempt_count = attempt_count + 1,
                    deadline_at = COALESCE(deadline_at, now() + make_interval(secs => :timeoutSeconds))
                WHERE id = :taskId AND state = 'QUEUED' AND cancel_requested_at IS NULL
                RETURNING *
                """, Maps.of(
                "taskId", taskId,
                "workerId", token.workerId(),
                "leaseId", token.leaseId(),
                "leaseSeconds", leaseSeconds,
                "timeoutSeconds", timeoutSeconds), rowMapper).stream().findFirst();
    }

    /** 本进程当前持有的 LEASED 任务(关闭 drain 与指标使用)。 */
    public List<RuntimeTask> scanHeldByWorker(UUID workerId) {
        return jdbc.query("SELECT * FROM runtime_tasks WHERE state = 'LEASED' "
                + "AND worker_id = :workerId", Maps.of("workerId", workerId), rowMapper);
    }

    /**
     * 该业务 Run 是否正被一个<b>存活</b>的 owner 执行(design §7、§11)。
     *
     * <p>启动恢复用它区分两种非终态 Run:
     * <ul>
     *   <li>有存活租约 —— 属于另一个仍在运行的 Worker,启动恢复<b>绝不</b>碰它;</li>
     *   <li>无存活租约(无任务行、或任务已停止持有、或租约已过期)
     *       —— 才是真正的孤儿,由启动恢复或过期扫描收敛。</li>
     * </ul>
     */
    public boolean hasLiveOwnerForWorkspaceRun(UUID runId) {
        Boolean live = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM runtime_tasks
                    WHERE workspace_run_id = :runId
                      AND state = 'LEASED'
                      AND lease_until > clock_timestamp()
                      AND (deadline_at IS NULL OR deadline_at > clock_timestamp())
                      AND cancel_requested_at IS NULL)
                """, Maps.of("runId", runId), Boolean.class);
        return Boolean.TRUE.equals(live);
    }

    /** 批量版本:一次查询给出全部"有存活 owner"的画布 Run id。 */
    public java.util.Set<UUID> workspaceRunsWithLiveOwner() {
        return new java.util.HashSet<>(jdbc.queryForList("""
                SELECT workspace_run_id FROM runtime_tasks
                WHERE workspace_run_id IS NOT NULL
                  AND state = 'LEASED'
                  AND lease_until > clock_timestamp()
                  AND (deadline_at IS NULL OR deadline_at > clock_timestamp())
                  AND cancel_requested_at IS NULL
                """, Map.of(), UUID.class));
    }

    /** 批量版本:全部"有存活 owner"的 GA Run id(design §11、§12)。 */
    public java.util.Set<UUID> gaRunsWithLiveOwner() {
        return new java.util.HashSet<>(jdbc.queryForList("""
                SELECT ga_run_id FROM runtime_tasks
                WHERE ga_run_id IS NOT NULL
                  AND state = 'LEASED'
                  AND lease_until > clock_timestamp()
                  AND (deadline_at IS NULL OR deadline_at > clock_timestamp())
                  AND cancel_requested_at IS NULL
                """, Map.of(), UUID.class));
    }

    /** 记录一次尝试的开始(与 lease 同事务)。 */
    public void insertAttempt(UUID attemptId, ExecutionToken token) {
        jdbc.update("""
                INSERT INTO runtime_task_attempts (id, task_id, execution_epoch, worker_id, lease_id, started_at)
                VALUES (:id, :taskId, :epoch, :workerId, :leaseId, now())
                """, Maps.of(
                "id", attemptId,
                "taskId", token.taskId(),
                "epoch", token.epoch(),
                "workerId", token.workerId(),
                "leaseId", token.leaseId()));
    }

    /**
     * 心跳续租(design §6.4):<b>先取任务行锁,再用实时数据库时钟核实</b>,最后续租。
     *
     * <p><b>为什么不能直接比较 {@code now()}。</b>PostgreSQL 的 {@code now()} 是
     * <b>事务开始时间</b>。一条条件 UPDATE 在等待目标行锁期间可能已经跨越租约
     * 到期,恢复执行后 {@code now()} 仍是事务开始时的旧值,于是实际已过期的租约被
     * 通过条件"复活";单线程心跳被一个任务的锁等待卡住时,还会连累其他任务的续租。
     * 因此本方法:
     * <ol>
     *   <li>在<b>当前事务</b>内设子语句级 {@code SET LOCAL lock_timeout /
     *       statement_timeout},给锁等待与语句执行设上界——一次受阻的续租不会拖垮
     *       整个心跳循环(仅在调用方已开启事务时生效);</li>
     *   <li>对目标行取 {@code FOR UPDATE};等待结束后读到的是<b>最新已提交版本</b>;</li>
     *   <li>用 {@code clock_timestamp()} 核实 token 三项匹配、仍是 LEASED、未取消、
     *       旧租约未过期、绝对 deadline 未过期;</li>
     *   <li>用 {@code clock_timestamp()} 续租;<b>deadline 绝不延长</b>,过期租约
     *       绝不复活。</li>
     * </ol>
     *
     * @return 1 表示续租成功;0 表示不满足条件(任务不存在/非 LEASED/token 不匹配/
     *         已取消/租约或 deadline 已过期)。
     * @throws org.springframework.dao.CannotAcquireLockException 锁等待超时:调用方
     *         必须视为"暂态、可重试",<b>绝不</b>据此判定失权
     */
    public int renewLease(ExecutionToken token, int leaseSeconds) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isActualTransactionActive()) {
            // 边界:锁等待 2s、语句执行 10s。禁用自动提交下这两条只在当前事务生效。
            jdbc.getJdbcTemplate().execute("SET LOCAL lock_timeout = '2s'");
            jdbc.getJdbcTemplate().execute("SET LOCAL statement_timeout = '10s'");
        }
        // 第 1 步:先取任务行锁。只读稳定列——绝不在锁等待之前求值时钟:
        // SELECT 列表里的 clock_timestamp() 会在行锁等待完成之前被求值,读到的
        // 仍是等待开始时的旧时间,正是"复活已过期租约"的根因。
        LeaseIdentity identity = jdbc.query("""
                SELECT state, worker_id, execution_epoch, lease_id, cancel_requested_at
                FROM runtime_tasks WHERE id = :taskId FOR UPDATE
                """, Maps.of("taskId", token.taskId()), (rs, rowNum) -> new LeaseIdentity(
                        rs.getString("state"),
                        rs.getObject("worker_id", UUID.class),
                        rs.getLong("execution_epoch"),
                        rs.getObject("lease_id", UUID.class),
                        rs.getObject("cancel_requested_at") != null)).stream().findFirst().orElse(null);
        if (identity == null
                || !"LEASED".equals(identity.state())
                || !token.workerId().equals(identity.workerId())
                || token.epoch() != identity.epoch()
                || !token.leaseId().equals(identity.leaseId())
                || identity.cancelled()) {
            return 0;
        }
        // 第 2 步:行锁已在手,再用实时数据库时钟核实旧租约与绝对 deadline 是否仍然有效。
        // 独立的第二条语句保证 clock_timestamp() 在取得行锁之后才求值。
        Boolean valid = jdbc.queryForObject("""
                SELECT lease_until > clock_timestamp()
                   AND (deadline_at IS NULL OR deadline_at > clock_timestamp())
                FROM runtime_tasks WHERE id = :taskId
                """, Maps.of("taskId", token.taskId()), Boolean.class);
        if (!Boolean.TRUE.equals(valid)) {
            return 0;
        }
        // 第 3 步:续租。deadline 绝不延长。
        return jdbc.update("""
                UPDATE runtime_tasks
                SET lease_until = clock_timestamp() + make_interval(secs => :leaseSeconds),
                    heartbeat_at = clock_timestamp()
                WHERE id = :taskId
                """, Maps.of("taskId", token.taskId(), "leaseSeconds", leaseSeconds));
    }

    /** 续租判定读取的稳定列(不含任何时钟表达式)。 */
    private record LeaseIdentity(String state, UUID workerId, long epoch, UUID leaseId,
                                 boolean cancelled) {
    }

    /** 本地执行器提交失败时按 token 条件释放认领并重新排队(design §6.2 第 6 步)。 */
    public int requeueAfterLocalFailure(ExecutionToken token, int delaySeconds, String reason) {
        return jdbc.update("""
                UPDATE runtime_tasks
                SET state = 'QUEUED', worker_id = NULL, lease_id = NULL,
                    lease_until = NULL, heartbeat_at = NULL,
                    available_at = now() + make_interval(secs => :delaySeconds),
                    recovery_reason = :reason
                WHERE id = :taskId AND state = 'LEASED'
                  AND worker_id = :workerId AND execution_epoch = :epoch AND lease_id = :leaseId
                """, Maps.of(
                "taskId", token.taskId(),
                "workerId", token.workerId(),
                "epoch", token.epoch(),
                "leaseId", token.leaseId(),
                "delaySeconds", delaySeconds,
                "reason", reason));
    }

    /**
     * 结束某次尝试的审计行。0 行是 benign(接管已撤销旧 attempt)。
     */
    public int endAttempt(UUID taskId, long epoch, String outcome, String failureReason) {
        return jdbc.update("""
                UPDATE runtime_task_attempts
                SET ended_at = now(), outcome = :outcome, failure_reason = :failureReason
                WHERE task_id = :taskId AND execution_epoch = :epoch AND ended_at IS NULL
                """, Maps.of(
                "taskId", taskId,
                "epoch", epoch,
                "outcome", outcome,
                "failureReason", failureReason));
    }

    /** 任务终态化:只在 token 仍然有效时生效(受保护终态写入)。 */
    public int terminalize(ExecutionToken token, RuntimeTaskState terminalState,
                           String errorCode, String recoveryReason) {
        return jdbc.update("""
                UPDATE runtime_tasks
                SET state = :state, last_error_code = :errorCode, recovery_reason = :recoveryReason
                WHERE id = :taskId AND state = 'LEASED'
                  AND worker_id = :workerId AND execution_epoch = :epoch AND lease_id = :leaseId
                """, Maps.of(
                "taskId", token.taskId(),
                "state", terminalState.code(),
                "errorCode", errorCode,
                "recoveryReason", recoveryReason,
                "workerId", token.workerId(),
                "epoch", token.epoch(),
                "leaseId", token.leaseId()));
    }

    /**
     * 过期 LEASED 任务扫描(design §7)。多个实例用 SKIP LOCKED 分批处理,
     * 锁 task 后由调用方再次核实过期。
     */
    public List<RuntimeTask> scanExpiredLeases(int limit) {
        return jdbc.query("""
                SELECT * FROM runtime_tasks
                WHERE state = 'LEASED' AND lease_until <= clock_timestamp()
                ORDER BY lease_until
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """, Maps.of("limit", limit), rowMapper);
    }

    /**
     * 接管:撤销旧 token 并进入 RECOVERING(design §7 第 1 步)。递增
     * execution_epoch,使旧执行器的一切条件写入立即落空。
     */
    public int beginRecovery(UUID taskId, long expectedEpoch, String reason) {
        // 过期判定用实时数据库时钟:本 UPDATE 恢复扫描事务可能先在别处等待过
        // 行锁,now()(事务开始时间)会把实际已过期的租约误判为未过期,漏掉
        // 一次该发生的恢复(下一轮扫描仍会兜底,但诚实失败被无谓推迟)。
        return jdbc.update("""
                UPDATE runtime_tasks
                SET state = 'RECOVERING',
                    execution_epoch = execution_epoch + 1,
                    worker_id = NULL, lease_id = NULL, lease_until = NULL, heartbeat_at = NULL,
                    recovery_reason = :reason
                WHERE id = :taskId AND state = 'LEASED' AND execution_epoch = :expectedEpoch
                  AND lease_until <= clock_timestamp()
                """, Maps.of("taskId", taskId, "expectedEpoch", expectedEpoch, "reason", reason));
    }

    /** 恢复判定安全:转回 QUEUED 并设置 available_at(design §7)。 */
    public int requeueFromRecovery(UUID taskId, int delaySeconds, String reason) {
        return jdbc.update("""
                UPDATE runtime_tasks
                SET state = 'QUEUED', available_at = now() + make_interval(secs => :delaySeconds),
                    recovery_reason = :reason
                WHERE id = :taskId AND state = 'RECOVERING'
                """, Maps.of("taskId", taskId, "delaySeconds", delaySeconds, "reason", reason));
    }

    /** 恢复判定不安全:诚实失败并提供核对记录(design §7 表)。 */
    public int failFromRecovery(UUID taskId, String errorCode, String reason) {
        return jdbc.update("""
                UPDATE runtime_tasks
                SET state = 'FAILED', last_error_code = :errorCode, recovery_reason = :reason
                WHERE id = :taskId AND state IN ('RECOVERING', 'QUEUED')
                """, Maps.of("taskId", taskId, "errorCode", errorCode, "reason", reason));
    }

    /** 取消:撤销当前 token 并写取消状态(design §6.5)。 */
    public int cancel(UUID taskId) {
        return jdbc.update("""
                UPDATE runtime_tasks
                SET state = 'CANCELLED',
                    cancel_requested_at = COALESCE(cancel_requested_at, now()),
                    worker_id = NULL, lease_id = NULL, lease_until = NULL, heartbeat_at = NULL
                WHERE id = :taskId AND state NOT IN ('SUCCEEDED', 'FAILED', 'CANCELLED')
                """, Maps.of("taskId", taskId));
    }

    /** 请求取消但保留当前 token,由执行事务自行收敛(协作式取消)。 */
    public int requestCancel(UUID taskId) {
        return jdbc.update("""
                UPDATE runtime_tasks
                SET cancel_requested_at = COALESCE(cancel_requested_at, now())
                WHERE id = :taskId AND state IN ('QUEUED', 'LEASED', 'RECOVERING')
                """, Maps.of("taskId", taskId));
    }

    /** 各状态的排队/持有计数,供指标与健康检查使用。 */
    public Map<String, Integer> countsByState() {
        Map<String, Integer> counts = new java.util.LinkedHashMap<>();
        jdbc.query("SELECT state, COUNT(*) AS total FROM runtime_tasks GROUP BY state",
                Map.of(), rs -> {
                    counts.put(rs.getString("state"), rs.getInt("total"));
                });
        return counts;
    }

    /** 队列最老等待时间(秒);没有排队任务时为 null。 */
    public Long oldestQueuedWaitSeconds() {
        return jdbc.queryForObject("SELECT EXTRACT(EPOCH FROM (now() - MIN(available_at)))::bigint "
                + "FROM runtime_tasks WHERE state = 'QUEUED'", Map.of(), Long.class);
    }

    /** 当前可用执行槽(GLOBAL)。没有可用槽时不认领新任务。 */
    public int availableGlobalSlots() {
        List<Integer> available = jdbc.queryForList(
                "SELECT capacity - used FROM runtime_capacity_slots WHERE slot_key = :slotKey",
                Maps.of("slotKey", RuntimeExecutionProtocol.SLOT_GLOBAL), Integer.class);
        // 配额行是迁移期预建的;缺失表示部署不完整,按"无可用槽"处理而不是
        // 抛异常——认领路径绝不应因为诊断查询失败而中断。
        return available.isEmpty() ? 0 : available.get(0);
    }
}
