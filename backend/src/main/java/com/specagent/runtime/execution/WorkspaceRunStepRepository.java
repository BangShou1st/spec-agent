package com.specagent.runtime.execution;

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
 * 文件名:WorkspaceRunStepRepository.java
 *
 * 用途:画布步骤账本 {@code workspace_run_steps} 的持久化出口(design §5.5)。
 *
 * <p><b>步骤身份不包含 attempt</b>({@code (run_id, step_key)} 为主键):接管后
 * 同一个逻辑步骤仍然复用同一行,因此"接管"不会被误识别成"新副作用"。这正是
 * 恢复时能够判断"这一步是否已经完成"的基础。
 *
 * <p>与 checkpoint 的原子性:步骤行的状态推进与它对应的业务结果写入必须在
 * <b>同一事务</b>里提交。否则会出现"步骤标记 SUCCEEDED 但业务结果不存在"
 * (恢复时误判为已完成而跳过)或"业务结果已写入但步骤仍是 IN_FLIGHT"
 * (恢复时误判为不确定而诚实失败)两种错误。
 */
@Repository
public class WorkspaceRunStepRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final RowMapper<WorkspaceRunStep> rowMapper;

    public WorkspaceRunStepRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.rowMapper = (rs, rowNum) -> new WorkspaceRunStep(
                rs.getObject("run_id", UUID.class),
                rs.getString("step_key"),
                rs.getString("input_hash"),
                WorkspaceRunStepState.fromCode(rs.getString("state")),
                rs.getLong("origin_epoch"),
                rs.getString("result_ref"),
                WorkspaceRunStepRetryClass.fromCode(rs.getString("retry_class")),
                rs.getTimestamp("updated_at").toInstant());
    }

    /**
     * 声明一个步骤为 PREPARED(已准备、尚未发起外部调用)。
     *
     * <p>幂等:<b>绝不降级</b>已有步骤的状态。如果该步骤已经 SUCCEEDED(例如
     * 接管后重放),重复声明不得把它退回 PREPARED——那会让恢复逻辑误以为需要
     * 重跑一个已完成的外部调用。输入 hash 不一致时拒绝(design §5.5"重试输入
     * 不一致时拒绝复用")。
     */
    public void prepare(UUID runId, String stepKey, String inputHash, long epoch,
                        WorkspaceRunStepRetryClass retryClass) {
        Optional<WorkspaceRunStep> existing = find(runId, stepKey);
        if (existing.isPresent()) {
            WorkspaceRunStep step = existing.get();
            // 已完成的步骤是最终事实:先返回,绝不因为"重放时算出的 hash 与历史
            // 记录不同"而拒绝一个根本不需要重跑的逻辑步骤。接管后的重放正会
            // 走到这里。
            if (step.state() == WorkspaceRunStepState.SUCCEEDED) {
                return;
            }
            if (inputHash != null && step.inputHash() != null
                    && !inputHash.equals(step.inputHash())) {
                throw new IllegalStateException(
                        "workspace step " + stepKey + " of run " + runId
                                + " already exists with a different input hash; refusing to reuse");
            }
            jdbc.update("""
                    UPDATE workspace_run_steps
                    SET input_hash = COALESCE(:inputHash, input_hash),
                        state = 'PREPARED', origin_epoch = :epoch, updated_at = now()
                    WHERE run_id = :runId AND step_key = :stepKey
                    """, Maps.of("runId", runId, "stepKey", stepKey,
                    "inputHash", inputHash, "epoch", epoch));
            return;
        }
        jdbc.update("""
                INSERT INTO workspace_run_steps
                    (run_id, step_key, input_hash, state, origin_epoch, retry_class, updated_at)
                VALUES (:runId, :stepKey, :inputHash, 'PREPARED', :epoch, :retryClass, now())
                ON CONFLICT (run_id, step_key) DO NOTHING
                """, Maps.of("runId", runId, "stepKey", stepKey, "inputHash", inputHash,
                "epoch", epoch, "retryClass", retryClass.code()));
    }

    /** 标记步骤已发起外部调用(结果尚不可知)。 */
    public void markInFlight(UUID runId, String stepKey, long epoch) {
        jdbc.update("""
                UPDATE workspace_run_steps
                SET state = 'IN_FLIGHT', origin_epoch = :epoch, updated_at = now()
                WHERE run_id = :runId AND step_key = :stepKey AND state <> 'SUCCEEDED'
                """, Maps.of("runId", runId, "stepKey", stepKey, "epoch", epoch));
    }

    /**
     * 标记步骤成功并记录可验证的结果引用。必须在与业务结果相同的事务内调用。
     * 已有 SUCCEEDED 是幂等 no-op(绝不重写结果引用)。
     */
    public void markSucceeded(UUID runId, String stepKey, long epoch, String resultRef) {
        jdbc.update("""
                UPDATE workspace_run_steps
                SET state = 'SUCCEEDED', origin_epoch = :epoch, result_ref = :resultRef,
                    updated_at = now()
                WHERE run_id = :runId AND step_key = :stepKey AND state <> 'SUCCEEDED'
                """, Maps.of("runId", runId, "stepKey", stepKey, "epoch", epoch,
                "resultRef", resultRef));
    }

    /**
     * 标记步骤结果不确定(外部调用已发生但结果未知)。恢复时这类步骤默认
     * <b>不重放</b>——这是"结果不确定的外部工具调用停止并提供核对记录"的
     * 数据基础(design §7)。
     */
    public void markUnknown(UUID runId, String stepKey, long epoch, String resultRef) {
        jdbc.update("""
                UPDATE workspace_run_steps
                SET state = 'UNKNOWN', origin_epoch = :epoch, result_ref = :resultRef,
                    updated_at = now()
                WHERE run_id = :runId AND step_key = :stepKey AND state <> 'SUCCEEDED'
                """, Maps.of("runId", runId, "stepKey", stepKey, "epoch", epoch,
                "resultRef", resultRef));
    }

    /** 标记步骤失败(可安全重试的失败)。 */
    public void markFailed(UUID runId, String stepKey, long epoch, String resultRef) {
        jdbc.update("""
                UPDATE workspace_run_steps
                SET state = 'FAILED', origin_epoch = :epoch, result_ref = :resultRef,
                    updated_at = now()
                WHERE run_id = :runId AND step_key = :stepKey AND state <> 'SUCCEEDED'
                """, Maps.of("runId", runId, "stepKey", stepKey, "epoch", epoch,
                "resultRef", resultRef));
    }

    public Optional<WorkspaceRunStep> find(UUID runId, String stepKey) {
        return jdbc.query("SELECT * FROM workspace_run_steps "
                        + "WHERE run_id = :runId AND step_key = :stepKey",
                Maps.of("runId", runId, "stepKey", stepKey), rowMapper).stream().findFirst();
    }

    /** 该 Run 的全部步骤,按 step_key 排序(恢复核验用)。 */
    public List<WorkspaceRunStep> findByRun(UUID runId) {
        return jdbc.query("SELECT * FROM workspace_run_steps WHERE run_id = :runId "
                + "ORDER BY step_key", Maps.of("runId", runId), rowMapper);
    }

    /** 该 Run 中处于指定状态的步骤(恢复判定用)。 */
    public List<WorkspaceRunStep> findByRunAndState(UUID runId, WorkspaceRunStepState state) {
        return jdbc.query("SELECT * FROM workspace_run_steps "
                        + "WHERE run_id = :runId AND state = :state ORDER BY step_key",
                Maps.of("runId", runId, "state", state.code()), rowMapper);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
