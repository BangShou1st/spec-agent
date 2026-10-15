package com.specagent.agent.runtime;

import com.specagent.access.ActorContextHolder;
import com.specagent.runtime.execution.ExecutionClaim;
import com.specagent.runtime.execution.ExecutionToken;
import com.specagent.runtime.execution.ExecutionTokenContext;
import com.specagent.runtime.execution.RuntimeTaskState;
import com.specagent.runtime.execution.TaskActor;
import com.specagent.runtime.execution.TaskExecutionAdapter;
import com.specagent.runtime.execution.TaskLifecyclePort;
import com.specagent.runtime.execution.TaskOwnershipLostException;
import com.specagent.runtime.execution.RuntimeTaskScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 文件名:WorkspaceTaskExecutionAdapter.java
 *
 * 用途:把公共调度层认领到的画布任务翻译成既有的周期服务调用
 * (design §10)。它是公共调度层与画布业务之间唯一的执行接缝。
 *
 * <p><b>token 的显式传递。</b>执行前把 token 绑定到
 * {@link ExecutionTokenContext},让深层受保护写入(Answer/Patch/Snapshot
 * 的 checkpoint)能够以该 token 去数据库核实所有权。绑定只覆盖本次执行,
 * 结束后无条件清除——API-only 线程永不携带 token,因此用户命令绝不会获得
 * 执行所有权。
 *
 * <p><b>成功的终态由业务周期写入。</b>周期服务在写业务 Run 终态时,受保护
 * 写入会顺带核实 token;调度任务的成功终态由
 * {@link WorkspaceMultiWorkerDriver} 收口。若业务周期已经把 Run 终态化为
 * FAILED(例如 stale target),任务按失败收口——两者不可能同时成立。
 */
@Component
public class WorkspaceTaskExecutionAdapter implements TaskExecutionAdapter {

    private static final Logger LOG = LoggerFactory.getLogger(WorkspaceTaskExecutionAdapter.class);

    /** 本适配器覆盖的画布 trigger 队列(与 {@link AgentRunTriggerType#code()} 一致)。 */
    private static final Set<String> TASK_TYPES = Set.of(
            AgentRunTriggerType.DECISION_CYCLE.code(),
            AgentRunTriggerType.ANSWER_CYCLE.code(),
            AgentRunTriggerType.GENERATE_SPEC.code(),
            AgentRunTriggerType.REGENERATE_NODE.code(),
            AgentRunTriggerType.NODE_QUERY.code(),
            AgentRunTriggerType.CONTINUE_CYCLE.code());

    private final RunWorker runWorker;
    private final AgentRunService agentRunService;

    public WorkspaceTaskExecutionAdapter(RunWorker runWorker,
                                         AgentRunService agentRunService) {
        this.runWorker = runWorker;
        this.agentRunService = agentRunService;
    }

    @Override
    public Set<String> taskTypes() {
        return TASK_TYPES;
    }

    @Override
    public void execute(ExecutionClaim claim) {
        UUID runId = claim.runId();
        ExecutionToken token = claim.token();
        AgentRun run = agentRunService.getRun(runId).orElse(null);
        if (run == null) {
            throw new IllegalStateException("Claimed agent run no longer exists: " + runId);
        }
        if (run.status().isTerminal()) {
            // 业务 Run 已终态:本次认领无事可做。调度任务按成功收口(业务事实
            // 已经落地),绝不重跑一个已完成的工作。
            LOG.info("Claimed agent run {} is already {}, treating the task as done",
                    runId, run.status());
            return;
        }

        // 执行所有权只在本次执行期间绑定;结束后无条件清除。
        // 用接管入口(executeClaimedRun):恢复不回退业务进度,因此被接管的 run
        // 可能已经是 PERSISTED(Answer 已落库而 STATE_UPDATE 失败)。终态仍被拒绝,
        // 真正的保护来自任务级 token 护栏。
        //
        // 执行主体同样只在本次执行期间绑定:它从**持久化任务行**重建(P2-D),
        // 绝不继承调度线程或 HTTP 线程。这样周期内的资源授权(项目成员校验)
        // 以真实发起者的身份展开,而不是"没有主体"或被提升为管理员。
        TaskActor actor = requirePersistedActor(claim);
        try (ActorContextHolder.Scope ignored = ActorContextHolder.bind(actor.toActorContext())) {
            ExecutionTokenContext.runWith(token, () -> runWorker.executeClaimedRun(run));
        }

        // 周期服务可能已经把 Run 终态化(失败或完成)。两者都算"业务事实已
        // 落地":任务终态由驱动按 Run 的真实结果收口,这里只做一致性断言。
        AgentRunStatus finalStatus = agentRunService.getRun(runId)
                .map(AgentRun::status).orElse(AgentRunStatus.FAILED);
        if (finalStatus == AgentRunStatus.FAILED) {
            throw new RunTerminalizedAsFailedException(runId);
        }
        if (!finalStatus.isTerminal()) {
            // 周期服务正常返回却未终态化:这在既有语义里意味着"等待用户/审批"
            // 等停靠形态,业务事实已经落地(例如 awaiting_approval),任务按成功
            // 收口是正确的——重新执行同一个 run 不会推进任何东西。
            LOG.info("Agent cycle for run {} returned with non-terminal status {}; "
                    + "treating the task as done (the run is parked, not runnable)",
                    runId, finalStatus);
        }
    }

    /** 周期服务已把 Run 终态化为失败:任务按失败收口。 */
    static final class RunTerminalizedAsFailedException extends RuntimeException {
        RunTerminalizedAsFailedException(UUID runId) {
            super("agent run " + runId + " was terminalized as FAILED by the cycle",
                    null, false, false);
        }
    }

    /**
     * 任务行的持久化主体。缺失即无法重建可信归属——失败关闭,绝不以
     * "没有主体"执行(那等于给后台任务开了一个隐形超级用户)。
     */
    private static TaskActor requirePersistedActor(ExecutionClaim claim) {
        TaskActor actor = claim.task() == null ? null : claim.task().actor();
        if (actor == null || actor.missingOrg()) {
            throw new IllegalStateException(
                    "runtime task " + claim.taskId()
                            + " has no persisted actor/org; refusing to execute without attribution");
        }
        return actor;
    }
}
