package com.specagent.assistant.runtime;

import com.specagent.assistant.GlobalAssistantErrorCode;
import com.specagent.assistant.conversation.GlobalAssistantRun;
import com.specagent.assistant.conversation.GlobalAssistantRunRepository;
import com.specagent.assistant.conversation.GlobalAssistantRunStatus;
import com.specagent.runtime.execution.ExecutionClaim;
import com.specagent.runtime.execution.RuntimeTask;
import com.specagent.runtime.execution.RuntimeTaskFamily;
import com.specagent.runtime.execution.RuntimeTaskState;
import com.specagent.runtime.execution.TaskLifecyclePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 文件名:GaTaskLifecyclePort.java
 *
 * 用途:全局助手 Run 与公共调度任务的生命周期衔接(design §6.2、§6.5、§7)。
 *
 * <p>两类写入都在调度层的任务行排他锁之内被调用,因此这里不需要再做所有权
 * 协调,只负责把调度事实翻译成 GA 业务事实:
 * <ul>
 *   <li>{@link #onClaimed}:认领只在调度层完成。GA 的业务态推进(CREATED →
 *       RUNNING)由 {@code GaExecutionPreparation} 在它自己的事务里完成——那里
 *       同时冻结模型/能力绑定。因此这里不重复推进状态,只做一致性断言。</li>
 *   <li>{@link #onTerminalized}:任务终态化时把业务 Run 落到终态。已有终态绝不被
 *       覆盖;GA 保留 CANCELLED 作为第三种终态(design §6.5)。</li>
 * </ul>
 *
 * <p><b>GA 恢复分两步(design §7、§11)。</b>第一步是"持久化排队 + 多会话并行 +
 * 按租约诚实终态化";经过验证的 checkpoint 恢复是第二步。因此本端口在恢复判定
 * 为"结果不确定"时,把 Run 诚实终态化为失败并保留可查询的错误码——绝不让一个
 * 租约过期的 GA Run 永远停在 RUNNING。
 */
@Component
public class GaTaskLifecyclePort implements TaskLifecyclePort {
    @Override public void onControlCancelled(RuntimeTask task) {
        lifecycle.cancelAndTerminalize(task.runId());
    }

    private static final Logger LOG = LoggerFactory.getLogger(GaTaskLifecyclePort.class);

    private final GlobalAssistantRunRepository runs;
    private final GlobalAssistantRunLifecycleService lifecycle;

    public GaTaskLifecyclePort(GlobalAssistantRunRepository runs,
                               GlobalAssistantRunLifecycleService lifecycle) {
        this.runs = runs;
        this.lifecycle = lifecycle;
    }

    @Override
    public RuntimeTaskFamily family() {
        return RuntimeTaskFamily.GLOBAL_ASSISTANT;
    }

    @Override
    public void onClaimed(ExecutionClaim claim) {
        GlobalAssistantRun run = runs.findById(claim.runId()).orElse(null);
        if (run == null) {
            throw new IllegalStateException(
                    "GA run " + claim.runId() + " disappeared before its task was claimed");
        }
        if (run.status().isTerminal()) {
            throw new IllegalStateException(
                    "GA run " + claim.runId() + " is already " + run.status()
                            + "; rolling back the task claim");
        }
        // 业务态推进留给 GaExecutionPreparation:它必须在同一事务里同时冻结
        // 模型绑定与能力目录,并把执行代次写成本次 token 的代次。这里重复推进
        // 会破坏那个原子边界。
    }

    @Override
    public void onTerminalized(ExecutionClaim claim, RuntimeTaskState taskState,
                               String errorCode, String reason) {
        GlobalAssistantRun run = runs.findById(claim.runId()).orElse(null);
        if (run == null) {
            LOG.warn("GA terminalization skipped: run {} no longer exists", claim.runId());
            return;
        }
        if (run.status().isTerminal()) {
            // 幂等:既有终态绝不被覆盖,也不追加第二次终态事件。
            return;
        }
        UUID threadId = run.threadId();
        UUID runId = run.id();
        switch (taskState) {
            case CANCELLED -> lifecycle.cancelAndTerminalize(runId);
            case SUCCEEDED -> {
                // 成功终态必须由执行路径自己写(它掌握助手正文与 UI 动作)。
                // 走到这里说明执行器返回了却没有终态化,那是不完整执行:
                // 诚实失败而不是伪造一条成功消息。
                LOG.warn("GA run {} reached SUCCEEDED task state without a terminal "
                        + "assistant message; failing honestly", runId);
                lifecycle.failWithoutContent(runId,
                        GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED,
                        "execution returned without a terminal result");
            }
            case FAILED -> lifecycle.failWithoutContent(runId, errorCode == null
                            ? GlobalAssistantErrorCode.GA_EXECUTION_FAILED : errorCode,
                    reason == null ? "execution failed" : reason);
            default -> throw new IllegalArgumentException(
                    "Unsupported terminal task state for GA: " + taskState);
        }
    }

    /** 面向用户的失败文案:只区分可操作的类别,绝不泄漏内部细节。 */
    private String failureText(String errorCode) {
        if (GlobalAssistantErrorCode.GA_PYTHON_UNAVAILABLE.equals(errorCode)) {
            return "全局助手 Python 服务不可用，请检查启动状态。";
        }
        return "这一步未能完成，请稍后重试。";
    }

    /**
     * 恢复判定后的业务收敛(design §7)。
     *
     * <p><b>为什么必须在这里写业务终态。</b>过期扫描把任务诚实失败时,GA Run 仍然
     * 停在 CREATED/RUNNING。若只改调度状态,用户会看到一个永远不会推进的"进行中"
     * 会话——而且它占着 {@code uq_ga_runs_single_active} 的唯一活跃槽,该会话将
     * <b>再也无法创建新的 Run</b>。因此恢复判定为不安全的终态化必须同步落到
     * 业务 Run 上。
     *
     * <p>本方法在恢复事务内被调用(已持有任务行排他锁),因此不存在并发所有者;
     * 已经终态的 Run 绝不被覆盖。
     */
    @Override
    public void onRecoveryDecision(RuntimeTask task, RecoveryDecision decision) {
        if (decision == RecoveryDecision.REQUEUED) {
            // 安全接管:业务 Run 保持原状,由新 Worker 从核验 checkpoint 继续。
            return;
        }
        if (task.gaRunId() == null) {
            return;
        }
        GlobalAssistantRun run = runs.findById(task.gaRunId()).orElse(null);
        if (run == null || run.status().isTerminal()) {
            return;
        }
        lifecycle.interruptAndTerminalize(run.id());
    }
}
