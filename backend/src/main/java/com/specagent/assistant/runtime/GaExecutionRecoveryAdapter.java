package com.specagent.assistant.runtime;

import com.specagent.runtime.execution.RecoveryAdapter;
import com.specagent.runtime.execution.RecoveryVerdict;
import com.specagent.runtime.execution.RuntimeTask;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * 文件名:GaExecutionRecoveryAdapter.java
 *
 * 用途:全局助手任务的接管核验(design §7 第二步的前置条件)。
 *
 * <p><b>默认不注册。</b>design §7 要求 GA 分两步:先做"持久化排队 + 多会话并行
 * + 按租约诚实终态化",再开放经过验证的 checkpoint 恢复。因此本适配器<b>只在
 * {@code spec.agent.execution.ga-recovery-enabled=true} 时</b>才装配;默认下
 * 租约过期的 GA 任务一律诚实失败(`EXECUTION_OUTCOME_UNKNOWN`),绝不自动重放
 * 一次可能已经发出的模型调用。
 *
 * <p><b>核验依据(只读,绝不发起外部调用):</b>
 * <ol>
 *   <li>存在 {@code RESERVED}/{@code UNKNOWN} 的调用 → 外部副作用可能已经发生
 *       且没有回执 → <b>不接管</b>(最常见形态:进程在发出调用之后、结果写回
 *       之前死亡);</li>
 *   <li>缺少执行绑定或 checkpoint head → 没有可沿用的快照与续跑点 → 不接管;</li>
 *   <li>以上皆无 → 允许接管,并沿用原模型/能力快照与<b>剩余</b>预算
 *       (调用账本与预算计数绝不重置)。</li>
 * </ol>
 *
 * <p>本适配器只读,且不改变任何调用账本状态。
 */
@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
        name = "spec.agent.execution.ga-recovery-enabled", havingValue = "true")
public class GaExecutionRecoveryAdapter implements RecoveryAdapter {

    /** 未决调用状态的稳定判定(与 V43 的 CHECK 约束一致)。 */
    private static final java.util.Set<String> UNRESOLVED_STATES =
            java.util.Set.of("RESERVED", "UNKNOWN");

    private final NamedParameterJdbcTemplate jdbc;

    public GaExecutionRecoveryAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String taskType() {
        return GaTaskTypes.EXECUTION;
    }

    @Override
    public RecoveryVerdict assess(RuntimeTask task) {
        UUID runId = task.gaRunId();
        if (runId == null) {
            return RecoveryVerdict.unsafe("recovery adapter received a non-GA task",
                    "task=" + task.id());
        }
        String base = "task=" + task.id() + " run=" + runId
                + " attempt=" + task.attemptCount();

        // 执行绑定是接管的前提:没有绑定就无法沿原模型/能力快照继续。
        Integer bindings = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ga_executions WHERE run_id = :runId",
                Map.of("runId", runId), Integer.class);
        if (bindings == null || bindings == 0) {
            return RecoveryVerdict.unsafe(
                    "no GA execution binding exists for the task; there is nothing to resume",
                    base);
        }

        // 未决调用:外部副作用可能已经发生而没有回执 → 默认不重放。
        var unresolved = jdbc.queryForList("""
                SELECT call_id, kind, status FROM ga_execution_calls
                WHERE run_id = :runId AND status IN ('RESERVED', 'UNKNOWN')
                ORDER BY call_sequence
                """, Map.of("runId", runId));
        if (!unresolved.isEmpty()) {
            StringBuilder record = new StringBuilder(base);
            for (var row : unresolved) {
                record.append(" | call=").append(row.get("call_id"))
                        .append(" kind=").append(row.get("kind"))
                        .append(" status=").append(row.get("status"));
            }
            record.append(" | budget preserved: modelCalls/toolCalls are never reset on takeover");
            return RecoveryVerdict.unsafe(
                    "GA run has " + unresolved.size() + " unresolved call(s) with possible "
                            + "external side effects; automatic takeover would risk replaying them",
                    record.toString());
        }

        // 检查点必须存在:终态化时用它校验输出,接管也从它继续。
        var checkpoints = jdbc.queryForList("""
                SELECT completed_checkpoint_id FROM ga_checkpoint_heads WHERE thread_id = (
                    SELECT thread_id FROM ga_executions WHERE run_id = :runId)
                """, Map.of("runId", runId));
        if (checkpoints.isEmpty()) {
            return RecoveryVerdict.unsafe(
                    "no GA checkpoint head exists; there is no verified checkpoint to resume from",
                    base);
        }

        return RecoveryVerdict.safe(
                "GA run has a persisted binding, no unresolved calls, and a checkpoint head; "
                        + "budget and the original model/capability snapshot are preserved");
    }
}
