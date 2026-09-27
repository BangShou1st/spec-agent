package com.specagent.agent.runtime;

import com.specagent.common.Json;
import com.specagent.common.Maps;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:AgentRunRepository.java
 *
 * 用途:AgentRun 的持久化仓库(agent_runs 表)。负责 run 的插入、状态推进
 * (CONTEXT_BUILT → MODEL_CALLED → REFLECTED → PERSISTED)、终态更新(完成/失败),
 * 以及各类 claim 方法——把 CREATED 状态的 run 原子地置为 RUNNING 供 worker 执行,
 * 是"命令 → 持久化 → Brain → 校验 → checkpoint"链路中 run 记录的唯一落库出口。
 */
@Repository
public class AgentRunRepository {

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final Json json;
    private final RowMapper<AgentRun> rowMapper;

    public AgentRunRepository(NamedParameterJdbcTemplate jdbcTemplate, Json json) {
        this.jdbcTemplate = jdbcTemplate;
        this.json = json;
        this.rowMapper = (rs, rowNum) -> new AgentRun(
                rs.getObject("id", UUID.class),
                rs.getObject("project_id", UUID.class),
                rs.getObject("route_id", UUID.class),
                AgentRunTriggerType.fromCode(rs.getString("trigger_type")),
                rs.getObject("input_node_id", UUID.class),
                rs.getObject("context_snapshot_id", UUID.class),
                rs.getObject("produced_node_id", UUID.class),
                rs.getObject("produced_answer_id", UUID.class),
                rs.getObject("produced_patch_id", UUID.class),
                rs.getObject("produced_spec_snapshot_id", UUID.class),
                AgentRunStatus.fromCode(rs.getString("status")),
                rs.getString("trace"),
                rs.getString("operation"),
                rs.getString("idempotency_key"),
                rs.getString("request_fingerprint"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toInstant(),
                rs.getObject("parent_run_id", UUID.class),
                rs.getObject("root_run_id", UUID.class),
                rs.getObject("cycle_index", Integer.class));
    }

    /**
     * 所有权代次 > 0(执行器持有租约)时,run 状态写入必须在同一条 UPDATE
     * 内同时满足三个条件(第三轮复核 R3-A 的闭合):
     * <ol>
     *   <li>全局所有权代次仍等于本执行器的代次——接管一旦提交,旧执行器
     *       的后续写入立即落空,与其线程在"检查"之后暂停了多久无关;</li>
     *   <li>run 行的认领代次不晚于本执行器代次;</li>
     *   <li>run 尚未终态——终态不可被任何执行器覆盖。</li>
     * </ol>
     * 代次为 0(无租约进程)不追加条件:没有执行器之争,保持历史行为。
     * 检查与写入在同一语句内完成,不存在"检查通过后暂停仍能写入"的窗口。
     */
    private static final String FENCED_STATUS_GUARD =
            " AND status NOT IN ('completed', 'failed')"
                    + " AND owner_epoch <= :ownerEpoch"
                    + " AND (SELECT epoch FROM executor_ownership WHERE id = 1) = :ownerEpoch";

    private static String fencedStatusGuard(long ownerEpoch) {
        return ownerEpoch > 0 ? FENCED_STATUS_GUARD : "";
    }

    /** 认领写入的 SET 子句:把认领代次落到 run 行,供后续写入验证。 */
    private static String ownerEpochSet(long ownerEpoch) {
        return ownerEpoch > 0 ? ", owner_epoch = :ownerEpoch" : "";
    }

    /** 认领语句的 WHERE 附加条件:丢锁执行器的认领必须落空(不能认领新任务)。 */
    private static String fencedClaimGuard(long ownerEpoch) {
        return ownerEpoch > 0
                ? " AND (SELECT epoch FROM executor_ownership WHERE id = 1) = :ownerEpoch "
                : " ";
    }

    public void save(AgentRun run) {
        String sql = """
                INSERT INTO agent_runs (id, project_id, route_id, trigger_type, input_node_id,
                                        context_snapshot_id, produced_node_id, produced_answer_id,
                                        produced_patch_id, produced_spec_snapshot_id, status, trace,
                                        operation, idempotency_key, request_fingerprint, created_at, completed_at,
                                        parent_run_id, root_run_id, cycle_index)
                VALUES (:id, :projectId, :routeId, :triggerType, :inputNodeId, :contextSnapshotId,
                        :producedNodeId, :producedAnswerId, :producedPatchId, :producedSpecSnapshotId,
                        :status, CAST(:trace AS jsonb), :operation, :idempotencyKey,
                        :requestFingerprint, :createdAt, :completedAt,
                        :parentRunId, :rootRunId, :cycleIndex)
                """;
        jdbcTemplate.update(sql, Maps.of(
                "id", run.id(),
                "projectId", run.projectId(),
                "routeId", run.routeId(),
                "triggerType", run.triggerType().code(),
                "inputNodeId", run.inputNodeId(),
                "contextSnapshotId", run.contextSnapshotId(),
                "producedNodeId", run.producedNodeId(),
                "producedAnswerId", run.producedAnswerId(),
                "producedPatchId", run.producedPatchId(),
                "producedSpecSnapshotId", run.producedSpecSnapshotId(),
                "status", run.status().code(),
                "trace", json.write(run.trace()),
                "operation", run.operation(),
                "idempotencyKey", run.idempotencyKey(),
                "requestFingerprint", run.requestFingerprint(),
                "createdAt", Timestamp.from(run.createdAt()),
                "completedAt", run.completedAt() == null ? null : Timestamp.from(run.completedAt()),
                "parentRunId", run.parentRunId(),
                "rootRunId", run.rootRunId(),
                "cycleIndex", run.cycleIndex()));
    }

    /**
     * 原子化的幂等插入:若同一 project/key 标识尚不存在则插入新行并返回 true;
     * 否则不写任何数据并返回 false。由组合部分唯一索引裁决并发创建者——
     * 任何一方都不会看到约束冲突,且每个 project/key 只会落库一行。
     */
    public boolean insertIfAbsent(AgentRun run) {
        String sql = """
                INSERT INTO agent_runs (id, project_id, route_id, trigger_type, input_node_id,
                                        context_snapshot_id, produced_node_id, produced_answer_id,
                                        produced_patch_id, produced_spec_snapshot_id, status, trace,
                                        operation, idempotency_key, request_fingerprint, created_at, completed_at,
                                        parent_run_id, root_run_id, cycle_index)
                VALUES (:id, :projectId, :routeId, :triggerType, :inputNodeId, :contextSnapshotId,
                        :producedNodeId, :producedAnswerId, :producedPatchId, :producedSpecSnapshotId,
                        :status, CAST(:trace AS jsonb), :operation, :idempotencyKey,
                        :requestFingerprint, :createdAt, :completedAt,
                        :parentRunId, :rootRunId, :cycleIndex)
                ON CONFLICT (project_id, idempotency_key)
                    WHERE idempotency_key IS NOT NULL DO NOTHING
                """;
        return jdbcTemplate.update(sql, Maps.of(
                "id", run.id(),
                "projectId", run.projectId(),
                "routeId", run.routeId(),
                "triggerType", run.triggerType().code(),
                "inputNodeId", run.inputNodeId(),
                "contextSnapshotId", run.contextSnapshotId(),
                "producedNodeId", run.producedNodeId(),
                "producedAnswerId", run.producedAnswerId(),
                "producedPatchId", run.producedPatchId(),
                "producedSpecSnapshotId", run.producedSpecSnapshotId(),
                "status", run.status().code(),
                "trace", json.write(run.trace()),
                "operation", run.operation(),
                "idempotencyKey", run.idempotencyKey(),
                "requestFingerprint", run.requestFingerprint(),
                "createdAt", Timestamp.from(run.createdAt()),
                "completedAt", run.completedAt() == null ? null : Timestamp.from(run.completedAt()),
                "parentRunId", run.parentRunId(),
                "rootRunId", run.rootRunId(),
                "cycleIndex", run.cycleIndex())) == 1;
    }

    /** 加载同一项目内幂等创建竞争中的"胜出"run(即实际落库的那条)。 */
    public Optional<AgentRun> findByProjectIdAndIdempotencyKey(UUID projectId,
                                                               String idempotencyKey) {
        String sql = "SELECT * FROM agent_runs "
                + "WHERE project_id = :projectId AND idempotency_key = :key";
        return jdbcTemplate.query(sql, Maps.of("projectId", projectId, "key", idempotencyKey), rowMapper)
                .stream().findFirst();
    }

    /**
     * 由指定父 run 派生出的最新子 run(若存在)。续跑协调器用它保证子 run
     * 只创建一次:已有子 run 的父 run 不再派生第二个。
     */
    public Optional<AgentRun> findChildByParentRunId(UUID parentRunId) {
        String sql = "SELECT * FROM agent_runs "
                + "WHERE parent_run_id = :parentRunId ORDER BY created_at DESC LIMIT 1";
        return jdbcTemplate.query(sql, Maps.of("parentRunId", parentRunId), rowMapper)
                .stream().findFirst();
    }

    /**
     * 终态写入(complete)。返回更新的行数;0 行表示终态守卫或所有权
     * 条件拒绝,由服务层判定语义(终态不可覆盖/租约丢失)。
     */
    public int updateStatus(UUID runId, AgentRunStatus status, Instant completedAt, String trace,
                            long ownerEpoch) {
        String sql = """
                UPDATE agent_runs SET status = :status, completed_at = :completedAt, trace = CAST(:trace AS jsonb)
                WHERE id = :runId
                """ + fencedStatusGuard(ownerEpoch);
        return jdbcTemplate.update(sql, Maps.of(
                "runId", runId,
                "status", status.code(),
                "completedAt", completedAt == null ? null : Timestamp.from(completedAt),
                "trace", json.write(trace),
                "ownerEpoch", ownerEpoch));
    }

    /**
     * 记录该 run 的上下文快照已构建并冻结(状态推进到 CONTEXT_BUILT)。
     * 返回更新的行数;0 行表示所有权/状态条件拒绝。
     */
    public int attachContext(UUID runId, UUID contextSnapshotId, String trace, long ownerEpoch) {
        String sql = """
                UPDATE agent_runs
                SET context_snapshot_id = :contextSnapshotId,
                    status = :status,
                    trace = CAST(:trace AS jsonb)
                WHERE id = :runId
                """ + fencedStatusGuard(ownerEpoch);
        return jdbcTemplate.update(sql, Maps.of(
                "runId", runId,
                "contextSnapshotId", contextSnapshotId,
                "status", AgentRunStatus.CONTEXT_BUILT.code(),
                "trace", json.write(trace),
                "ownerEpoch", ownerEpoch));
    }

    /**
     * 记录该 run 已调用模型适配器(状态推进到 MODEL_CALLED)。
     */
    public int markModelCalled(UUID runId, String trace, long ownerEpoch) {
        return updateStatusWithTrace(runId, AgentRunStatus.MODEL_CALLED, trace, ownerEpoch);
    }

    /**
     * 记录已对模型的提案执行过反思校验门(状态推进到 REFLECTED)。
     */
    public int markReflected(UUID runId, String trace, long ownerEpoch) {
        return updateStatusWithTrace(runId, AgentRunStatus.REFLECTED, trace, ownerEpoch);
    }

    /**
     * 记录该 run 持久化产出的节点(状态推进到 PERSISTED)。
     */
    public int markPersistedNode(UUID runId, UUID producedNodeId, String trace, long ownerEpoch) {        String sql = """
                UPDATE agent_runs
                SET produced_node_id = :producedNodeId,
                    status = :status,
                    trace = CAST(:trace AS jsonb)
                WHERE id = :runId
                """ + fencedStatusGuard(ownerEpoch);
        return jdbcTemplate.update(sql, Maps.of(
                "runId", runId,
                "producedNodeId", producedNodeId,
                "status", AgentRunStatus.PERSISTED.code(),
                "trace", json.write(trace),
                "ownerEpoch", ownerEpoch));
    }

    /**
     * 把审批阶段产出的节点挂到一条已处于终态的 run 上,不改动其状态和完成时间。
     * 执行归属在受理事务中;原始 run 只是补记产出的持久化效果引用,
     * 供续跑协调器判断。状态与 trace 保持终态化时的原样。
     */
    public void attachApprovalProducedNode(UUID runId, UUID producedNodeId) {
        String sql = """
                UPDATE agent_runs
                SET produced_node_id = :producedNodeId
                WHERE id = :runId
                """;
        jdbcTemplate.update(sql, Maps.of(
                "runId", runId,
                "producedNodeId", producedNodeId));
    }

    /**
     * 记录该 run 持久化产出的回答(状态推进到 PERSISTED)。
     */
    public int markPersistedAnswer(UUID runId, UUID producedAnswerId, String trace, long ownerEpoch) {
        String sql = """
                UPDATE agent_runs
                SET produced_answer_id = :producedAnswerId,
                    status = :status,
                    trace = CAST(:trace AS jsonb)
                WHERE id = :runId
                """ + fencedStatusGuard(ownerEpoch);
        return jdbcTemplate.update(sql, Maps.of(
                "runId", runId,
                "producedAnswerId", producedAnswerId,
                "status", AgentRunStatus.PERSISTED.code(),
                "trace", json.write(trace),
                "ownerEpoch", ownerEpoch));
    }

    /**
     * 记录该 run 持久化产出的回答补丁(状态推进到 PERSISTED)。
     */
    public int markPersistedAnswerPatch(UUID runId, UUID producedPatchId, String trace, long ownerEpoch) {
        String sql = """
                UPDATE agent_runs
                SET produced_patch_id = :producedPatchId,
                    status = :status,
                    trace = CAST(:trace AS jsonb)
                WHERE id = :runId
                """ + fencedStatusGuard(ownerEpoch);
        return jdbcTemplate.update(sql, Maps.of(
                "runId", runId,
                "producedPatchId", producedPatchId,
                "status", AgentRunStatus.PERSISTED.code(),
                "trace", json.write(trace),
                "ownerEpoch", ownerEpoch));
    }

    /**
     * 记录该 run 持久化产出的规格快照(状态推进到 PERSISTED)。
     */
    public int markPersistedSpecSnapshot(UUID runId, UUID producedSpecSnapshotId, String trace, long ownerEpoch) {
        String sql = """
                UPDATE agent_runs
                SET produced_spec_snapshot_id = :producedSpecSnapshotId,
                    status = :status,
                    trace = CAST(:trace AS jsonb)
                WHERE id = :runId
                """ + fencedStatusGuard(ownerEpoch);
        return jdbcTemplate.update(sql, Maps.of(
                "runId", runId,
                "producedSpecSnapshotId", producedSpecSnapshotId,
                "status", AgentRunStatus.PERSISTED.code(),
                "trace", json.write(trace),
                "ownerEpoch", ownerEpoch));
    }

    /**
     * 把 run 标记为 FAILED(终态)。原子状态转换规则(第三轮复核的 fail()
     * 闭合):只允许把<strong>非终态</strong>的 run 终态化——已完成的
     * COMPLETED 与已有的 FAILED(含新所有者写入的失败原因)绝不覆盖;
     * 持有租约的执行器还必须满足所有权条件(全局代次匹配 + 认领代次不晚
     * 于本代次)。丢锁执行器的 fail 落空(0 行),本地记录故障,业务恢复
     * 由当前合法所有者负责。返回更新的行数;0 行是幂等 benign(重复失败
     * 上报、已被新所有者终态化等),不是错误。
     */
    public int fail(UUID runId, String trace, long ownerEpoch) {
        String sql = """
                UPDATE agent_runs
                SET status = :status,
                    completed_at = :completedAt,
                    trace = CAST(:trace AS jsonb)
                WHERE id = :runId AND status NOT IN ('completed', 'failed')
                """ + (ownerEpoch > 0
                        ? " AND owner_epoch <= :ownerEpoch"
                          + " AND (SELECT epoch FROM executor_ownership WHERE id = 1) = :ownerEpoch"
                        : "");
        return jdbcTemplate.update(sql, Maps.of(
                "runId", runId,
                "status", AgentRunStatus.FAILED.code(),
                "completedAt", Timestamp.from(Instant.now()),
                "trace", json.write(trace),
                "ownerEpoch", ownerEpoch));
    }

    private int updateStatusWithTrace(UUID runId, AgentRunStatus status, String trace, long ownerEpoch) {
        String sql = """
                UPDATE agent_runs
                SET status = :status,
                    trace = CAST(:trace AS jsonb)
                WHERE id = :runId
                """ + fencedStatusGuard(ownerEpoch);
        return jdbcTemplate.update(sql, Maps.of(
                "runId", runId,
                "status", status.code(),
                "trace", json.write(trace),
                "ownerEpoch", ownerEpoch));
    }

    public Optional<AgentRun> findById(UUID id) {
        String sql = "SELECT * FROM agent_runs WHERE id = :id";
        return jdbcTemplate.query(sql, Maps.of("id", id), rowMapper).stream().findFirst();
    }

    public List<AgentRun> findByProject(UUID projectId) {
        String sql = "SELECT * FROM agent_runs WHERE project_id = :projectId ORDER BY created_at";
        return jdbcTemplate.query(sql, Maps.of("projectId", projectId), rowMapper);
    }

    /**
     * 项目的全部非终态 run,按创建顺序排列。支撑工作台"进行中的 run"读取,
     * 让前端在页面刷新后重建在途 run 注册表。
     */
    public List<AgentRun> findActiveByProject(UUID projectId) {
        String sql = """
                SELECT * FROM agent_runs
                WHERE project_id = :projectId AND status NOT IN ('completed', 'failed')
                ORDER BY created_at
                """;
        return jdbcTemplate.query(sql, Maps.of("projectId", projectId), rowMapper);
    }

    /**
     * 持久化了指定 Answer 的全部 run,按创建顺序。供回答修复/恢复流程找到
     * 原始尝试的冻结上下文快照(回答前状态用 {@code context_snapshot_id},
     * 回答后状态用 DECISION_STARTED 事件载荷)。
     */
    public List<AgentRun> findByProducedAnswerId(UUID producedAnswerId) {
        String sql = "SELECT * FROM agent_runs WHERE produced_answer_id = :answerId ORDER BY created_at";
        return jdbcTemplate.query(sql, Map.of("answerId", producedAnswerId), rowMapper);
    }

    /**
     * 全库范围内的全部非终态 run,按创建顺序排列。仅供启动期孤儿恢复使用:
     * 单实例部署下,进程启动时任何非终态 run 都属于已死执行器。
     */
    public List<AgentRun> findNonTerminal() {
        String sql = """
                SELECT * FROM agent_runs
                WHERE status NOT IN ('completed', 'failed')
                ORDER BY created_at
                """;
        return jdbcTemplate.query(sql, Maps.of(), rowMapper);
    }

    /**
     * 原子地领取最老的一条排队中的 decision-cycle run:把状态从 CREATED
     * 移到 RUNNING。无可领取的 run 时返回空。单条语句完成领取,
     * 配合 FOR UPDATE SKIP LOCKED 防止并发 worker 重复执行。
     */
    public Optional<AgentRun> claimNextDecisionCycleRun(long ownerEpoch) {
        String sql = """
                UPDATE agent_runs SET status = :running, owner_epoch = :ownerEpoch
                WHERE id = (
                    SELECT id FROM agent_runs
                    WHERE trigger_type = :trigger AND status = :created
                    """ + fencedClaimGuard(ownerEpoch) + """
                    ORDER BY created_at
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING *
                """;
        return jdbcTemplate.query(sql, Maps.of(
                        "running", AgentRunStatus.RUNNING.code(),
                        "trigger", AgentRunTriggerType.DECISION_CYCLE.code(),
                        "created", AgentRunStatus.CREATED.code(),
                        "ownerEpoch", ownerEpoch),
                rowMapper).stream().findFirst();
    }

    /**
     * 原子地领取最老的一条排队中的 answer-cycle run:把状态从 CREATED 移到 RUNNING。
     */
    public Optional<AgentRun> claimNextAnswerCycleRun(long ownerEpoch) {
        String sql = """
                UPDATE agent_runs SET status = :running, owner_epoch = :ownerEpoch
                WHERE id = (
                    SELECT id FROM agent_runs
                    WHERE trigger_type = :trigger AND status = :created
                    """ + fencedClaimGuard(ownerEpoch) + """
                    ORDER BY created_at
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING *
                """;
        return jdbcTemplate.query(sql, Maps.of(
                        "running", AgentRunStatus.RUNNING.code(),
                        "trigger", AgentRunTriggerType.ANSWER_CYCLE.code(),
                        "created", AgentRunStatus.CREATED.code(),
                        "ownerEpoch", ownerEpoch),
                rowMapper).stream().findFirst();
    }

    /**
     * 原子地按 id 领取一条指定的排队中 answer-cycle run。领取条件始终包含
     * CREATED 状态,因此已被任何人领取(或已执行)的 run 不会被重复领取。
     * 自己入队了 run 的调用方应使用本方法而非 {@link #claimNextAnswerCycleRun()}:
     * 队列是共享的,最老的排队 run 未必是自己那条。
     */
    public Optional<AgentRun> claimAnswerCycleRun(UUID runId, long ownerEpoch) {
        String sql = """
                UPDATE agent_runs SET status = :running, owner_epoch = :ownerEpoch
                WHERE id = CAST(:id AS uuid)
                  AND trigger_type = :trigger AND status = :created
                """ + fencedClaimGuard(ownerEpoch) + """
                RETURNING *
                """;
        return jdbcTemplate.query(sql, Maps.of(
                        "running", AgentRunStatus.RUNNING.code(),
                        "id", runId.toString(),
                        "trigger", AgentRunTriggerType.ANSWER_CYCLE.code(),
                        "created", AgentRunStatus.CREATED.code(),
                        "ownerEpoch", ownerEpoch),
                rowMapper).stream().findFirst();
    }

    /** 原子地领取最老的一条排队中的 artifact 生成 run。 */
    public Optional<AgentRun> claimNextArtifactRun(long ownerEpoch) {
        return claimNextByTrigger(AgentRunTriggerType.GENERATE_SPEC, ownerEpoch);
    }

    /** 原子地按 id 领取一条指定的排队中 artifact 生成 run。 */
    public Optional<AgentRun> claimArtifactRun(UUID runId, long ownerEpoch) {
        String sql = """
                UPDATE agent_runs SET status = :running, owner_epoch = :ownerEpoch
                WHERE id = CAST(:id AS uuid)
                  AND trigger_type = :trigger AND status = :created
                """ + fencedClaimGuard(ownerEpoch) + """
                RETURNING *
                """;
        return jdbcTemplate.query(sql, Maps.of(
                        "running", AgentRunStatus.RUNNING.code(),
                        "id", runId.toString(),
                        "trigger", AgentRunTriggerType.GENERATE_SPEC.code(),
                        "created", AgentRunStatus.CREATED.code(),
                        "ownerEpoch", ownerEpoch),
                rowMapper).stream().findFirst();
    }

    /** 原子地领取最老的一条排队中的自治续跑 run。 */
    public Optional<AgentRun> claimNextContinueRun(long ownerEpoch) {
        return claimNextByTrigger(AgentRunTriggerType.CONTINUE_CYCLE, ownerEpoch);
    }

    /** 原子地领取最老的一条排队中的 replacement(重生成节点)run。 */
    public Optional<AgentRun> claimNextRegenerateRun(long ownerEpoch) {
        return claimNextByTrigger(AgentRunTriggerType.REGENERATE_NODE, ownerEpoch);
    }

    private Optional<AgentRun> claimNextByTrigger(AgentRunTriggerType trigger, long ownerEpoch) {
        String sql = """
                UPDATE agent_runs SET status = :running, owner_epoch = :ownerEpoch
                WHERE id = (
                    SELECT id FROM agent_runs
                    WHERE trigger_type = :trigger AND status = :created
                    """ + fencedClaimGuard(ownerEpoch) + """
                    ORDER BY created_at
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING *
                """;
        return jdbcTemplate.query(sql, Maps.of(
                        "running", AgentRunStatus.RUNNING.code(),
                        "trigger", trigger.code(),
                        "created", AgentRunStatus.CREATED.code(),
                        "ownerEpoch", ownerEpoch),
                rowMapper).stream().findFirst();
    }

    /**
     * 原子地按 id 领取一条指定的排队中 decision-cycle run。领取条件始终包含
     * CREATED 状态,因此已被任何人领取(或已执行)的 run 不会被重复领取。
     */
    public Optional<AgentRun> claimDecisionCycleRun(UUID runId, long ownerEpoch) {
        String sql = """
                UPDATE agent_runs SET status = :running, owner_epoch = :ownerEpoch
                WHERE id = CAST(:id AS uuid)
                  AND trigger_type = :trigger AND status = :created
                """ + fencedClaimGuard(ownerEpoch) + """
                RETURNING *
                """;
        return jdbcTemplate.query(sql, Maps.of(
                        "running", AgentRunStatus.RUNNING.code(),
                        "id", runId.toString(),
                        "trigger", AgentRunTriggerType.DECISION_CYCLE.code(),
                        "created", AgentRunStatus.CREATED.code(),
                        "ownerEpoch", ownerEpoch),
                rowMapper).stream().findFirst();
    }

    /**
     * 原子地领取最老的一条排队中的 node-query run:把状态从 CREATED 移到 RUNNING。
     */
    public Optional<AgentRun> claimNextNodeQueryRun(long ownerEpoch) {
        String sql = """
                UPDATE agent_runs SET status = :running, owner_epoch = :ownerEpoch
                WHERE id = (
                    SELECT id FROM agent_runs
                    WHERE trigger_type = :trigger AND status = :created
                    """ + fencedClaimGuard(ownerEpoch) + """
                    ORDER BY created_at
                    LIMIT 1
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING *
                """;
        return jdbcTemplate.query(sql, Maps.of(
                        "running", AgentRunStatus.RUNNING.code(),
                        "trigger", AgentRunTriggerType.NODE_QUERY.code(),
                        "created", AgentRunStatus.CREATED.code(),
                        "ownerEpoch", ownerEpoch),
                rowMapper).stream().findFirst();
    }

    /**
     * 原子地按 id 领取一条指定的排队中 node-query run。领取条件始终包含
     * CREATED 状态,因此已被任何人领取(或已执行)的 run 不会被重复领取。
     */
    public Optional<AgentRun> claimNodeQueryRun(UUID runId, long ownerEpoch) {
        String sql = """
                UPDATE agent_runs SET status = :running, owner_epoch = :ownerEpoch
                WHERE id = CAST(:id AS uuid)
                  AND trigger_type = :trigger AND status = :created
                """ + fencedClaimGuard(ownerEpoch) + """
                RETURNING *
                """;
        return jdbcTemplate.query(sql, Maps.of(
                        "running", AgentRunStatus.RUNNING.code(),
                        "id", runId.toString(),
                        "trigger", AgentRunTriggerType.NODE_QUERY.code(),
                        "created", AgentRunStatus.CREATED.code(),
                        "ownerEpoch", ownerEpoch),
                rowMapper).stream().findFirst();
    }
}
