package com.specagent.agent.runtime;

import com.specagent.common.Maps;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * 文件名:ContinuationCheckRepository.java
 *
 * 用途:基于 {@code agent_run_continuation_checks} 表的持久化续跑 outbox。
 *
 * 每个终态 run 一行:{@code requested_at} 是终态化提交的时间,
 * {@code processed_at} 是分发器评估完它读到的那个 {@code request_generation}
 * 的时间。表里没有任何语义字段——协调器决策时重新读取持久化的 run 事实,
 * 绝不读这张表。afterCommit 之后崩溃会留下待处理行,由恢复扫描器兜底;
 * PARKED_APPROVAL 之后再次请求会把代数加一并重新打开评估。
 */
@Repository
public class ContinuationCheckRepository implements com.specagent.common.CapabilityCompletionPort {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ContinuationCheckRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** Scope comes from durable rows, never from a completion payload.
     * Always enqueue, even if this transaction cannot yet see parent terminalization:
     * its consumer may already be parking on our uncommitted PENDING row. The outbox
     * generation serializes both requests; the coordinator enforces terminality and
     * refuses failed parents. An early nonterminal evaluation remains retryable.
     */
    @Override
    public void completed(UUID invocationId) {
        jdbcTemplate.update("""
                INSERT INTO agent_run_continuation_checks
                    (run_id, requested_at, processed_at, request_generation)
                SELECT r.id, CURRENT_TIMESTAMP, NULL, 1
                  FROM capability_invocations i JOIN agent_runs r ON r.id = i.run_id
                 WHERE i.id = :id AND i.project_id = r.project_id
                ON CONFLICT (run_id) DO UPDATE
                  SET requested_at = CURRENT_TIMESTAMP, processed_at = NULL,
                      request_generation = agent_run_continuation_checks.request_generation + 1
                """, Maps.of("id", invocationId));
    }

    /**
     * 为一个终态 run 请求续跑评估。每次请求都会把代数加一并重新打开评估:
     * 已处理过的行(例如 PARKED_APPROVAL 之后又被接受)会在"新代数"下重置为
     * 待处理,因此旧代数在途的过期完成绝不可能把新请求标记为已处理
     * (ABA 安全:它只会标记 0 行)。
     */
    public void request(UUID runId) {
        String sql = """
                INSERT INTO agent_run_continuation_checks
                    (run_id, requested_at, processed_at, request_generation)
                VALUES (:runId, CURRENT_TIMESTAMP, NULL, 1)
                ON CONFLICT (run_id) DO UPDATE
                    SET requested_at = CURRENT_TIMESTAMP, processed_at = NULL,
                        request_generation =
                            agent_run_continuation_checks.request_generation + 1
                """;
        jdbcTemplate.update(sql, Maps.of("runId", runId));
    }

    /**
     * 待处理检查按最老优先取出,带条数上限,单次轮询绝不全表扫描。
     *
     * <p><b>worker 归属(design §10)。</b>多 Worker 下必须由数据库裁决归属,否则
     * 两个实例会同时评估同一批检查。这里用 {@code FOR UPDATE SKIP LOCKED} 加一次
     * 会过期的认领:认领者崩溃后 claim 自然过期,其他实例可以重新认领——
     * 绝不允许一个死掉的 worker 永久搁置一条检查。
     *
     * @param workerId 本进程的执行身份;null 表示不做归属(单执行器模式的历史行为)
     * @param claimSeconds 认领的有效期(秒)
     */
    public List<ContinuationCheck> claimPending(int limit, UUID workerId, int claimSeconds) {
        if (workerId == null) {
            // 单执行器模式:没有并发消费者,保持历史查询(不写认领列,
            // 因此也不会在该模式下引入新的列依赖)。
            String sql = """
                    SELECT run_id, request_generation FROM agent_run_continuation_checks
                    WHERE processed_at IS NULL
                    ORDER BY requested_at
                    LIMIT :limit
                    """;
            return jdbcTemplate.query(sql, Maps.of("limit", limit),
                    (rs, rowNum) -> new ContinuationCheck(
                            rs.getObject("run_id", UUID.class),
                            rs.getLong("request_generation")));
        }
        String sql = """
                UPDATE agent_run_continuation_checks
                SET claimed_by = :workerId,
                    claim_expires_at = now() + make_interval(secs => :claimSeconds)
                WHERE run_id IN (
                    SELECT run_id FROM agent_run_continuation_checks
                    WHERE processed_at IS NULL
                      AND (claim_expires_at IS NULL OR claim_expires_at <= now()
                           OR claimed_by = :workerId)
                    ORDER BY requested_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                )
                RETURNING run_id, request_generation
                """;
        return jdbcTemplate.query(sql, Maps.of(
                        "limit", limit, "workerId", workerId, "claimSeconds", claimSeconds),
                (rs, rowNum) -> new ContinuationCheck(
                        rs.getObject("run_id", UUID.class),
                        rs.getLong("request_generation")));
    }

    /** 待处理检查按最老优先取出(单执行器模式的历史入口)。 */
    public List<ContinuationCheck> findPending(int limit) {
        return claimPending(limit, null, 0);
    }

    /**
     * 释放某条检查的认领,使其立即可以被重新评估。评估失败时调用,避免让一条
     * 瞬时失败的检查等到 claim 过期。
     */
    public int releaseClaim(UUID runId, UUID workerId) {
        String sql = """
                UPDATE agent_run_continuation_checks
                SET claimed_by = NULL, claim_expires_at = NULL
                WHERE run_id = :runId AND claimed_by = :workerId AND processed_at IS NULL
                """;
        return jdbcTemplate.update(sql, Maps.of("runId", runId, "workerId", workerId));
    }

    /** 单个 run 的待处理代数(若存在)。 */
    public java.util.Optional<ContinuationCheck> findPendingByRunId(UUID runId) {
        String sql = """
                SELECT run_id, request_generation FROM agent_run_continuation_checks
                WHERE run_id = :runId AND processed_at IS NULL
                """;
        return jdbcTemplate.query(sql, Maps.of("runId", runId),
                (rs, rowNum) -> new ContinuationCheck(
                        rs.getObject("run_id", UUID.class),
                        rs.getLong("request_generation")))
                .stream().findFirst();
    }

    /** 单个 run 最近一次请求的代数(无论是否已处理)。 */
    public long currentGeneration(UUID runId) {
        String sql = """
                SELECT request_generation FROM agent_run_continuation_checks
                WHERE run_id = :runId
                """;
        Long generation = jdbcTemplate.queryForObject(
                sql, Maps.of("runId", runId), Long.class);
        if (generation == null) {
            throw new IllegalStateException(
                    "No continuation check for run: " + runId);
        }
        return generation;
    }

    /**
     * 把"被评估的那个代数"精确标记为已处理,返回标记的行数:0 表示并发的
     * 再次请求已使本代数被取代——新代数保持待处理并由恢复流程收敛;
     * 调用方绝不能把 0 当成本代数成功。
     */
    public int markProcessed(UUID runId, long generation) {
        String sql = """
                UPDATE agent_run_continuation_checks
                SET processed_at = CURRENT_TIMESTAMP
                WHERE run_id = :runId
                  AND request_generation = :generation
                  AND processed_at IS NULL
                """;
        return jdbcTemplate.update(sql, Maps.of(
                "runId", runId, "generation", generation));
    }
}
