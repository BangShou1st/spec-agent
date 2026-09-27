package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunFailureService;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.runtime.AgentRunTriggerType;
import com.specagent.agent.protocol.ModelContractException;
import com.specagent.agent.protocol.AgentEvent;
import com.specagent.agent.runtime.ContinuationDispatchService;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:RunWorker.java
 *
 * 用途:排队 run 的后台执行器。在"命令 → 持久化 → Brain → 校验 →
 * checkpoint"链路中,它是消费端:从各队列认领 run,并按 trigger type 分发到
 * 对应的周期服务:
 * - DECISION_CYCLE → DecisionCycleService:单次 DECISION 问题起草,
 *       带 policy + 执行。
 * - ANSWER_CYCLE → AnswerCycleService:2 次调用收敛,带 policy + 执行。
 * - GENERATE_SPEC → ArtifactCycleService:单次 ARTIFACT_GENERATION 调用,
 *       保留 grounding 守卫。
 * - REGENERATE_NODE → ReplacementCycleService:单次 DECISION 决定内容,
 *       拓扑提交是确定性的。 */
@Component
public class RunWorker {

    private static final Logger LOG = LoggerFactory.getLogger(RunWorker.class);

    private final RunService runService;
    private final AgentRunService agentRunService;
    private final AgentRunFailureService agentRunFailureService;
    private final AgentRunEventService eventService;
    private final AnswerCycleService answerCycleService;
    private final DecisionCycleService decisionCycleService;
    private final ArtifactCycleService artifactCycleService;
    private final ReplacementCycleService replacementCycleService;
    private final NodeQueryService nodeQueryService;
    private final ContinuationCycleService continuationCycleService;
    private final ContinuationDispatchService continuationDispatch;
    private final ExecutionFence executionFence;

    public RunWorker(RunService runService,
                            AgentRunService agentRunService,
                            AgentRunFailureService agentRunFailureService,
                            AgentRunEventService eventService,
                            AnswerCycleService answerCycleService,
                            DecisionCycleService decisionCycleService,
                            ArtifactCycleService artifactCycleService,
                            ReplacementCycleService replacementCycleService,
                            NodeQueryService nodeQueryService,
                            ContinuationCycleService continuationCycleService,
                            ContinuationDispatchService continuationDispatch,
                            ExecutionFence executionFence) {
        this.runService = runService;
        this.agentRunService = agentRunService;
        this.agentRunFailureService = agentRunFailureService;
        this.eventService = eventService;
        this.answerCycleService = answerCycleService;
        this.decisionCycleService = decisionCycleService;
        this.artifactCycleService = artifactCycleService;
        this.replacementCycleService = replacementCycleService;
        this.nodeQueryService = nodeQueryService;
        this.continuationCycleService = continuationCycleService;
        this.continuationDispatch = continuationDispatch;
        this.executionFence = executionFence;
    }

    /** 从每个队列各认领并执行至多一个排队 run。 */
    public void tryClaimAndExecute() {
        // 先试 DECISION_CYCLE,再试 ANSWER_CYCLE,再试 NODE_QUERY。
        runService.claimNext().ifPresent(this::executeRun);
        runService.claimNextAnswerCycle().ifPresent(this::executeRun);
        runService.claimNextArtifact().ifPresent(this::executeRun);
        runService.claimNextRegenerate().ifPresent(this::executeRun);
        runService.claimNextNodeQuery().ifPresent(this::executeRun);
        runService.claimNextContinue().ifPresent(this::executeRun);
    }

    /**
     * 按触发类型把认领到的 run 分发到对应处理器。
     *
     * fail-closed 入口:worker 只执行刚被认领的 {@code RUNNING} 行。
     * 直接传入的 {@code CREATED} 行(未经认领)会被拒绝,防止测试或调用方
     * 绕过 claim-and-execute 路径;终态 {@code COMPLETED}/{@code FAILED} 行
     * 绝不重复执行——重复投递必须改走
     * {@link ContinuationDispatchService#process}。
     */
    public void executeRun(AgentRun run) {
        // 执行入口的同步所有权验证:租约丢失(数据库重启、会话被终止等)
        // 后,本进程绝不继续执行新认领的 run。
        executionFence.assertOwnership();
        AgentRun latest = agentRunService.getRun(run.id()).orElse(run);
        if (latest.status() != AgentRunStatus.RUNNING) {
            throw new IllegalStateException(
                    "RunWorker only executes freshly claimed RUNNING runs: " + run.id()
                            + " is " + latest.status());
        }
        switch (latest.triggerType()) {
            case ANSWER_CYCLE -> executeAnswerCycle(latest);
            case NODE_QUERY -> executeNodeQuery(latest);
            case GENERATE_SPEC -> executeArtifactGeneration(latest);
            case REGENERATE_NODE -> executeRegenerate(latest);
            case CONTINUE_CYCLE -> executeContinuationCycle(latest);
            default -> executeDecisionCycle(latest);
        }
    }

    /**
     * spec snapshot 生成:一次 ARTIFACT_GENERATION 调用,加上
     * {@link ArtifactCycleService} 中保留的 grounding 守卫。
     */
    private void executeArtifactGeneration(AgentRun run) {
        UUID runId = run.id();
        try {
            artifactCycleService.generateSpec(run);
        } catch (RuntimeException ex) {
            failIfNotTerminal(runId, ex);
            throw ex;
        }
    }

    /**
     * 替换:一次 DECISION 决定内容,拓扑提交由
     * {@link ReplacementCycleService} 确定性地完成。
     */
    private void executeRegenerate(AgentRun run) {
        UUID runId = run.id();
        try {
            Map<String, Object> input = readRunInput(runId);
            UUID sourceRouteId = input.containsKey("routeId")
                    ? UUID.fromString((String) input.get("routeId")) : run.routeId();
            UUID targetNodeId = input.containsKey("nodeId")
                    ? UUID.fromString((String) input.get("nodeId")) : run.inputNodeId();
            String instruction = (String) input.get("freeText");
            if (sourceRouteId == null || targetNodeId == null) {
                throw new IllegalStateException(
                        "Replacement run is missing input parameters");
            }
            replacementCycleService.regenerate(
                    run, run.projectId(), sourceRouteId, targetNodeId, instruction);
        } catch (RuntimeException ex) {
            failIfNotTerminal(runId, ex);
            throw ex;
        }
    }

    /**
     * 问题起草:单次 DECISION,加 {@link DecisionCycleService} 中的
     * policy/执行链。
     */
    private void executeDecisionCycle(AgentRun run) {
        UUID runId = run.id();
        try {
            decisionCycleService.draftQuestion(run, explicitRouteIdOf(runId, run));
            evaluateContinuationAfterTerminal(runId);
        } catch (RuntimeException ex) {
            failIfNotTerminal(runId, ex);
            throw ex;
        }
    }

    /**
     * 重建入队时记录的 route 选择。
     *
     * 客户端要求显式 route(多 route 并行工作)时返回 run 自己的
     * route id;run 跟随项目 Active route 时返回 null——即历史行为,
     * 包括 Active 指针中途变动时 fail-closed。
     */
    private UUID explicitRouteIdOf(UUID runId, AgentRun run) {
        Map<String, Object> input = readRunInput(runId);
        return "EXPLICIT".equals(input.get("routeSelection")) ? run.routeId() : null;
    }

    /**
     * 回答周期:2 次调用收敛,带 policy + 执行。
     * 从持久化的 run 事件 payload 中读取输入参数。
     */
    private void executeAnswerCycle(AgentRun run) {
        UUID runId = run.id();
        try {
            // 从 RUN_CREATED 事件 payload 读取输入参数。
            Map<String, Object> input = readRunInput(runId);
            String operation = run.operation() != null ? run.operation() : "ANSWER_TIP";
            UUID selectedOptionId = input.containsKey("selectedOptionId")
                    ? UUID.fromString((String) input.get("selectedOptionId")) : null;
            // 多选回答携带完整的选择列表;回退到旧的单个 id,
            // 保证多选功能上线之前的 run 仍能工作。
            List<UUID> selectedOptionIds = readSelectedOptionIds(input, selectedOptionId);
            String freeText = (String) input.get("freeText");
            UUID answerId = input.containsKey("answerId")
                    ? UUID.fromString((String) input.get("answerId")) : null;
            AgentEvent.PersistenceIntent persistenceIntent = input.containsKey("persistenceIntent")
                    ? AgentEvent.PersistenceIntent.valueOf((String) input.get("persistenceIntent"))
                    : null;
            UUID explicitRouteId = "EXPLICIT".equals(input.get("routeSelection")) ? run.routeId() : null;

            AnswerCycleResult result;
            if ("RESUME_ANSWER".equals(operation) && answerId != null) {
                result = answerCycleService.resumeAnswer(run, run.projectId(), answerId,
                        persistenceIntent, explicitRouteId);
            } else {
                result = answerCycleService.submitAnswer(run, run.projectId(), selectedOptionIds, freeText,
                        persistenceIntent, explicitRouteId);
            }
            // 历史 checkpoint 恢复刻意不产生任何新的图事实。仅恢复型的
            // run 之后不要创建自治续跑:route 里已经有更靠后的 tip 了。
            if (!"historical_recovery".equals(result.status())) {
                evaluateContinuationAfterTerminal(runId);
            }
        } catch (RuntimeException ex) {
            failIfNotTerminal(runId, ex);
            throw ex;
        }
    }

    /** 优先读 payload 列表;为 null 或缺失时回退到旧的单个 id。 */
    private List<UUID> readSelectedOptionIds(Map<String, Object> input, UUID selectedOptionId) {
        Object raw = input.get("selectedOptionIds");
        if (raw instanceof List<?> list && !list.isEmpty()) {
            return list.stream()
                    .map(item -> UUID.fromString(String.valueOf(item)))
                    .toList();
        }
        return selectedOptionId == null ? List.of() : List.of(selectedOptionId);
    }

    /**
     * 自治续跑:在 {@link ContinuationCycleService} 中做一次全新的 DECISION。
     */
    private void executeContinuationCycle(AgentRun run) {
        UUID runId = run.id();
        try {
            continuationCycleService.executeContinuation(run);
            evaluateContinuationAfterTerminal(runId);
        } catch (RuntimeException ex) {
            failIfNotTerminal(runId, ex);
            throw ex;
        }
    }

    /**
     * 所有允许合法续跑的周期(decision、answer、continuation)共用的唯一
     * 终态续跑钩子。终态化服务已在同一事务里提交了 continuation-check 请求,
     * 因此这个钩子只负责派发低延迟快速通道——尽力而为:派发失败仅记日志并
     * 保持 pending,交给 {@link ContinuationDispatchService#recoverPending()},
     * 绝不会让已经 COMPLETED 的 run 变成失败。在受管事务内,派发会等到
     * afterCommit 之后再执行;若前置的终态写入已提交,则派发就地内联执行。
     * 任何周期服务都不直接调用派发器,也不传入任何执行结果、policy 判定或
     * 模型观察——输入始终只有一个 run id。
     */
    private void evaluateContinuationAfterTerminal(UUID runId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            dispatchContinuationBestEffort(runId);
                        }
                    });
        } else {
            dispatchContinuationBestEffort(runId);
        }
    }

    /**
     * 单个终态 run 的续跑检查的尽力而为快速通道投递。这里只隔离"终态之后"
     * 的投递失败——周期执行的异常永远到不了本方法(每个周期在自己的 catch
     * 里先终态化再抛出,本钩子在其之后才运行)。
     */
    void dispatchContinuationBestEffort(UUID runId) {
        try {
            continuationDispatch.process(runId);
        } catch (RuntimeException ex) {
            LOG.warn("Continuation fast-path dispatch deferred for run {}: {}",
                    runId, ex.getMessage());
        }
    }

    /**
     * 崩溃恢复入口:重放因 afterCommit 丢失而遗留的 pending continuation
     * check。由轮询循环在认领之后调用;单个坏行绝不阻塞整个队列。
     */
    public void recoverPendingContinuationChecks() {
        continuationDispatch.recoverPending();
    }

    /**
     * 节点查询:一次 DECISION 调用,回答关于某个节点的情境化问题;
     * 构造上只读(变更会变成 pending proposal)。
     */
    private void executeNodeQuery(AgentRun run) {
        UUID runId = run.id();
        try {
            Map<String, Object> input = readRunInput(runId);
            // routeId 是可选的:游离节点(floating node)查询时 route 为
            // null,仅以锚点节点作为上下文。
            Object routeRaw = input.get("routeId");
            UUID routeId = routeRaw == null || String.valueOf(routeRaw).isBlank()
                    ? run.routeId() : UUID.fromString((String) routeRaw);
            UUID nodeId = input.containsKey("nodeId")
                    ? UUID.fromString((String) input.get("nodeId")) : run.inputNodeId();
            String question = (String) input.get("question");
            if (nodeId == null || question == null) {
                throw new IllegalStateException("Node query run is missing input parameters");
            }
            nodeQueryService.executeNodeQuery(run, routeId, nodeId, question);
        } catch (RuntimeException ex) {
            failIfNotTerminal(runId, ex);
            throw ex;
        }
    }

    /**
     * 从该 run 的 RUN_CREATED 事件读取输入参数。
     */
    private Map<String, Object> readRunInput(UUID runId) {
        List<AgentRunEvent> events = eventService.findByRunId(runId);
        return events.stream()
                .filter(e -> "RUN_CREATED".equals(e.eventType()))
                .map(AgentRunEvent::payload)
                .findFirst()
                .orElse(Map.of());
    }

    private void failIfNotTerminal(UUID runId, RuntimeException ex) {
        String reason = failureStepFor(ex);
        LOG.warn("Agent run {} failed: {}", runId, reason);
        AgentRun latest = agentRunService.getRun(runId).orElse(null);
        if (latest != null && latest.status() != AgentRunStatus.FAILED
                && latest.status() != AgentRunStatus.COMPLETED) {
            // 状态转换与 RUN_FAILED 事件由失败服务在同一 REQUIRES_NEW 事务
            // 内原子提交;仅当状态转换真正生效时才存在"本次失败已发生"的
            // 业务事件。所有权拒绝只记本地诊断,绝不改变业务解释。
            AgentRunFailureService.FailureOutcome outcome =
                    agentRunFailureService.fail(runId, "failed:" + reason, reason);
            if (outcome == AgentRunFailureService.FailureOutcome.OWNERSHIP_REFUSED) {
                LOG.warn("Agent run {} failure not applied (ownership refused); "
                        + "recovery belongs to the current owner", runId);
            }
        }
    }

    /** 未到达终态的 run 的稳定失败码。 */
    private String failureStepFor(RuntimeException ex) {
        return RunFailureReasons.reasonCode(ex);
    }
}
