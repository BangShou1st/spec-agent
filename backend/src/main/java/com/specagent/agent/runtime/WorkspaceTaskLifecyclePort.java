package com.specagent.agent.runtime;

import com.specagent.runtime.execution.ExecutionClaim;
import com.specagent.runtime.execution.RuntimeTask;
import com.specagent.runtime.execution.RuntimeTaskFamily;
import com.specagent.runtime.execution.RuntimeTaskState;
import com.specagent.runtime.execution.TaskLifecyclePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:WorkspaceTaskLifecyclePort.java
 *
 * 用途:画布业务 Run 与公共调度任务的生命周期衔接(design §6.2 第 4 步、
 * §6.5)。
 *
 * <p>两类写入都在调度层的任务行排他锁之内被调用,因此本类不需要再做所有权
 * 协调;它只负责把调度事实翻译成业务事实:
 * <ul>
 *   <li>{@link #onClaimed}:把刚被认领的 Run 推进为 RUNNING 并记录本次执行
 *       代次。与任务认领同一事务提交,因此对外绝不出现"任务已 LEASED 而
 *       业务 Run 仍 CREATED"的状态。</li>
 *   <li>{@link #onTerminalized}:把业务 Run 落到终态。已有终态绝不被覆盖
 *       ——重复终态请求是幂等 no-op,不追加第二次终态事件。</li>
 * </ul>
 *
 * <p>注意:业务 Run 的进度状态(CONTEXT_BUILT / MODEL_CALLED / ...)仍由各
 * 周期服务在受保护写入中推进;本类只处理边界上的两次转换。
 */
@Component
public class WorkspaceTaskLifecyclePort implements TaskLifecyclePort {

    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceTaskLifecyclePort.class);

    private final AgentRunClaimRepository claims;
    private final AgentRunRepository runs;
    private final com.specagent.agent.runevent.AgentRunEventService events;

    public WorkspaceTaskLifecyclePort(AgentRunClaimRepository claims, AgentRunRepository runs,
                                      com.specagent.agent.runevent.AgentRunEventService events) {
        this.claims = claims;
        this.runs = runs;
        this.events = events;
    }

    @Override
    public RuntimeTaskFamily family() {
        return RuntimeTaskFamily.WORKSPACE;
    }

    @Override
    public void onClaimed(ExecutionClaim claim) {
        UUID runId = claim.runId();
        if (claims.markClaimed(runId, claim.token().epoch()) != 1) {
            // 竞争失败:该 Run 已不是 CREATED(被别的 Worker 抢先认领,或已被
            // 用户操作终态化)。抛出即回滚整个认领事务,任务回到 QUEUED 由下一
            // 轮重新评估——绝不带着一个未推进的 Run 进入执行。
            throw new IllegalStateException(
                    "agent run " + runId + " is no longer claimable; "
                            + "rolling back the task claim");
        }
    }

    @Override
    public void onTerminalized(ExecutionClaim claim, RuntimeTaskState taskState,
                               String errorCode, String reason) {
        terminalizeRun(claim.runId(), taskState, errorCode);
    }

    /**
     * 恢复判定后的业务收敛(design §7)。
     *
     * <p><b>为什么必须在这里写业务终态。</b>过期扫描把任务诚实失败时,业务 Run
     * 仍然停在非终态(典型:Answer 已写而 STATE_UPDATE 未完成,Run 是
     * {@code persisted})。若只改调度状态,用户会看到一个永远不会推进的"进行中"
     * 任务——前端刷新页面就会重建幽灵进度。因此恢复判定为不安全的终态化必须同步
     * 落到业务 Run 上。
     *
     * <p>本方法在恢复事务内被调用,该事务已持有任务行排他锁,因此不存在并发
     * 所有者;已经终态的 Run 绝不被覆盖。
     */
    @Override
    public void onRecoveryDecision(RuntimeTask task, RecoveryDecision decision) {
        if (decision == RecoveryDecision.REQUEUED) {
            // 安全接管:业务 Run 保持原状,由新 Worker 从核验 checkpoint 继续。
            return;
        }
        if (task.workspaceRunId() == null) {
            return;
        }
        terminalizeRun(task.workspaceRunId(), RuntimeTaskState.FAILED,
                task.lastErrorCode());
    }

    private void terminalizeRun(UUID runId, RuntimeTaskState taskState, String errorCode) {
        AgentRunStatus target = switch (taskState) {
            case SUCCEEDED -> AgentRunStatus.COMPLETED;
            // 画布当前只有 COMPLETED/FAILED 两种终态;取消映射为 FAILED + 专用
            // 取消原因(design §6.5),由前端明确展示。
            case CANCELLED, FAILED -> AgentRunStatus.FAILED;
            default -> null;
        };
        if (target == null) {
            throw new IllegalArgumentException("Unsupported terminal task state: " + taskState);
        }
        AgentRun current = runs.findById(runId).orElse(null);
        if (current == null) {
            LOG.warn("Terminalization skipped: agent run {} no longer exists", runId);
            return;
        }
        if (current.status().isTerminal()) {
            // 幂等:既有终态绝不被覆盖,也不追加第二次终态事件。
            return;
        }
        String trace = terminalTrace(target, errorCode, null, current.trace());
        // 恢复路径不携带 token(旧所有者已失效),也不应改写 owner_epoch:
        // 传 0 表示"这是恢复事务的终态写入",任务的排他锁已经提供互斥。
        runs.updateStatus(runId, target, Instant.now(), trace, 0L);
        if (target == AgentRunStatus.FAILED) {
            // 事件与业务终态在同一事务(调度层的终态事务,持任务行锁):凡是
            // 由调度层把 Run 收敛为失败的地方——驱动收口、恢复判定、取消——
            // 都必须留下"本次失败已发生"的 RUN_FAILED 事件,否则前端与审计
            // 只看到一个没有解释的终态。已终态的 Run 在上方幂等返回,绝不
            // 追加第二次终态事件(与 AgentRunFailureService 的协议一致)。
            String reason = errorCode == null || errorCode.isBlank()
                    ? RunFailureReasons.INTERRUPTED_BY_RESTART : errorCode;
            events.append(runId, com.specagent.agent.runevent.AgentRunPhase.FAILED,
                    "RUN_FAILED", RunFailureReasons.payload(reason));
        }
    }

    /**
     * 终态 trace 沿用既有 {@code failed:<CODE>} 约定(与
     * {@link AgentRunFailureService} 一致),使前端与恢复服务无需新增解析规则。
     */
    private String terminalTrace(AgentRunStatus target, String errorCode, String reason,
                                 String existingTrace) {
        if (target == AgentRunStatus.COMPLETED) {
            return existingTrace == null ? "completed" : existingTrace;
        }
        String code = errorCode == null || errorCode.isBlank()
                ? RunFailureReasons.INTERRUPTED_BY_RESTART : errorCode;
        return "failed:" + code;
    }
}
