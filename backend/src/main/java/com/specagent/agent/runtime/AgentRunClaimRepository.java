package com.specagent.agent.runtime;

import com.specagent.common.Maps;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:AgentRunClaimRepository.java
 *
 * 用途:画布 Run 的<b>认领候选</b>选择与认领写入(design §6.2 第 2/4 步)。
 *
 * <p>与旧的 {@code AgentRunRepository.claim*} 的区别:这里不再在单条 UPDATE
 * 里塞进"全库所有权代次"条件(那是单执行器边界的产物),而是只负责"选出并
 * 锁住一个 CREATED 行",把它推进为 RUNNING 并记录本次执行的代次。容量槽与
 * 项目范围的取得由 {@link WorkspaceTaskClaimService} 在<b>同一事务</b>内按
 * 固定锁序完成。
 *
 * <p>{@code FOR UPDATE SKIP LOCKED} 保证并发 Worker 不会重复执行同一行;
 * {@code status = 'created'} 条件保证已被任何人认领的行不会被二次认领。
 */
@Repository
public class AgentRunClaimRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AgentRunClaimRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 按队列顺序锁定下一个待认领的画布 Run。
     *
     * @param triggerTypes 允许的 trigger 类型;空表示不限(轮询器的公平认领)
     * @param excluded     已尝试过、本轮不再考虑的 run id
     */
    public List<UUID> lockNextQueuedRunIds(List<String> triggerTypes, List<UUID> excluded, int limit) {
        return jdbc.queryForList("""
                SELECT id FROM agent_runs
                WHERE status = 'created'
                  AND trigger_type IN (:triggerTypes)
                  AND NOT (id = ANY(:excluded))
                ORDER BY created_at, id
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """, Maps.of(
                "triggerTypes", triggerTypes,
                "excluded", excluded.isEmpty() ? new UUID[0] : excluded.toArray(new UUID[0]),
                "limit", limit), UUID.class);
    }

    /** 按 id 锁定一条指定的待认领 Run(测试驱动与"自己入队的 run"路径)。 */
    public boolean lockQueuedRunById(UUID runId) {
        return jdbc.queryForList("SELECT id FROM agent_runs WHERE id = :runId "
                + "AND status = 'created' FOR UPDATE SKIP LOCKED",
                Maps.of("runId", runId), UUID.class).size() == 1;
    }

    /** 该 Run 已入队等待的时长(秒),供饥饿与等待指标使用。 */
    public Long queuedAgeSeconds(UUID runId) {
        return jdbc.queryForObject(
                "SELECT EXTRACT(EPOCH FROM (now() - created_at))::bigint FROM agent_runs WHERE id = :runId",
                Maps.of("runId", runId), Long.class);
    }

    /** Run 的项目归属(范围解析的唯一来源,绝不来自请求参数)。 */
    public UUID projectIdOf(UUID runId) {
        return jdbc.queryForObject("SELECT project_id FROM agent_runs WHERE id = :runId",
                Maps.of("runId", runId), UUID.class);
    }

    public String triggerTypeOf(UUID runId) {
        return jdbc.queryForObject("SELECT trigger_type FROM agent_runs WHERE id = :runId",
                Maps.of("runId", runId), String.class);
    }

    /**
     * 认领写入:把选中行推进为 RUNNING 并记录本次执行代次。
     *
     * <p><b>绝不回退业务进度(design §5.1)。</b>只有 {@code created} 行被推进为
     * {@code running};已经推进过的行(如 {@code persisted}——接管发生时业务
     * 进度已经越过创建的典型形态)保持原状态,只更新本行的执行代次。恢复只改变
     * 调度状态,绝不把 {@code CONTEXT_BUILT}/{@code PERSISTED} 等业务进度重置为
     * {@code CREATED}。
     *
     * <p>返回 0 行表示该 Run 已终态或不存在:调用方必须回滚整个认领事务。认领的
     * 互斥由 runtime_tasks 的任务行锁保证(只有一个 Worker 能赢得租约),不再依赖
     * "Run 仍是 created"这一旧单执行器条件。
     */
    public int markClaimed(UUID runId, long executionEpoch) {
        return jdbc.update("""
                UPDATE agent_runs
                SET status = CASE WHEN status = 'created' THEN 'running' ELSE status END,
                    owner_epoch = :executionEpoch
                WHERE id = :runId AND status NOT IN ('completed', 'failed')
                """, Maps.of("runId", runId, "executionEpoch", executionEpoch));
    }

    /**
     * 认领前复核:该 Run 是否仍然处于可执行状态。用于"范围冲突后重新扫描"
     * 的场景——同一个 Run 被其他 Worker 抢先认领时必须放弃它。
     */
    public boolean isStillQueued(UUID runId) {
        return jdbc.queryForList("SELECT id FROM agent_runs WHERE id = :runId AND status = 'created'",
                Maps.of("runId", runId), UUID.class).size() == 1;
    }
}
