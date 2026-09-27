package com.specagent.agent.runtime;

import com.specagent.agent.gates.PatchReflectionGate;

import com.specagent.agent.protocol.ModelContractException;

import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunFailureService;
import com.specagent.agent.action.ActionExecutionContext;
import com.specagent.agent.protocol.*;
import com.specagent.agent.decision.AgentBrainResponseValidator;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.gates.PatchReflectionGate;
import com.specagent.agent.decision.ReflectionResult;
import com.specagent.agent.action.ActionEligibilityGate;
import com.specagent.agent.snapshot.LegacyFrozenInputUnavailableException;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.runevent.RunProgressRecorder;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.context.ContextBuilder;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.context.ContextSnapshotRepository;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatch;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.patch.Claim;
import com.specagent.workspace.patch.ClaimKind;
import com.specagent.workspace.patch.ClaimStatus;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteHistoryResolver;
import com.specagent.workspace.route.RouteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:AnswerCycleService.java
 *
 * 用途:回答循环的执行器——恰好 2 次串行模型调用(STATE_UPDATE + DECISION),
 * 取代遗留的 3 次调用路径(INTERPRET_ANSWER + DRAFT_ANSWER_PATCH + DRAFT_NODE)。
 *
 * 在"命令 → 持久化 → Brain → 校验 → checkpoint"链路中,它负责被领取后的
 * run 实际执行:落库不可变 Answer → 构造冻结输入快照调用 Brain → 校验响应
 * → 持久化补丁 checkpoint 与状态更新。
 *
 * 流程:
 * 1. 先持久化不可变 Answer(在任何模型调用之前)。
 * 2. 检查是否已存在 AnswerPatch(修复门)。
 * 3. 无补丁时:第 1 次调用 STATE_UPDATE → claims 落锚 → 持久化补丁 checkpoint。
 * 4. 重建包含该 Answer/Patch 的事后(post-state)快照。
 * 5. 第 2 次调用 DECISION(从事后快照)→ 观察结果 + 动作提案。
 * 6. 策略评估 → 自动执行或提交确认。
 *
 * 保留修复语义:Answer 一旦存在,重试会从安全的补丁 checkpoint 续跑,
 * 绝不创建第二个 Answer。
 */
@Service
public class AnswerCycleService {

    private static final Logger LOG = LoggerFactory.getLogger(AnswerCycleService.class);

    private final AgentRunService agentRunService;
    private final ExecutionFence executionFence;
    private final AgentRunFailureService agentRunFailureService;
    private final ContextBuilder contextBuilder;
    private final AgentInputSnapshotBuilder snapshotBuilder;
    private final AgentDecisionEngine decisionEngine;
    private final AnswerService answerService;
    private final AnswerPatchService answerPatchService;
    private final PatchReflectionGate patchReflectionGate;
    private final DecisionExecutionService decisionExecution;
    private final AgentRunEventService eventService;
    private final NodeService nodeService;
    private final RouteRepository routeRepository;
    private final com.specagent.workspace.project.ProjectRepository projectRepository;
    private final ContextSnapshotRepository contextSnapshotRepository;
    private final com.specagent.agent.snapshot.AgentInputProjectionRepository projectionRepository;
    private final ActionEligibilityGate actionEligibilityGate;
    private final AgentTracePort semanticTraceRecorder;
    private final RunProgressRecorder progressRecorder;
    private final RouteHistoryResolver routeHistoryResolver;
    private final org.springframework.transaction.support.TransactionTemplate transactionTemplate;

    public AnswerCycleService(AgentRunService agentRunService,
                                ExecutionFence executionFence,
                              AgentRunFailureService agentRunFailureService,
                              ContextBuilder contextBuilder,
                              AgentInputSnapshotBuilder snapshotBuilder,
                              AgentDecisionEngine decisionEngine,
                              AnswerService answerService,
                              AnswerPatchService answerPatchService,
                              PatchReflectionGate patchReflectionGate,
                              DecisionExecutionService decisionExecution,
                              AgentRunEventService eventService,
                              NodeService nodeService,
                              RouteRepository routeRepository,
                              com.specagent.workspace.project.ProjectRepository projectRepository,
                              ContextSnapshotRepository contextSnapshotRepository,
                              com.specagent.agent.snapshot.AgentInputProjectionRepository projectionRepository,
                              AgentTracePort semanticTraceRecorder,
                              ActionEligibilityGate actionEligibilityGate,
                              RunProgressRecorder progressRecorder,
                              RouteHistoryResolver routeHistoryResolver,
                              org.springframework.transaction.support.TransactionTemplate transactionTemplate) {
        this.agentRunService = agentRunService;
        this.executionFence = executionFence;
        this.agentRunFailureService = agentRunFailureService;
        this.contextBuilder = contextBuilder;
        this.snapshotBuilder = snapshotBuilder;
        this.decisionEngine = decisionEngine;
        this.answerService = answerService;
        this.answerPatchService = answerPatchService;
        this.patchReflectionGate = patchReflectionGate;
        this.decisionExecution = decisionExecution;
        this.eventService = eventService;
        this.nodeService = nodeService;
        this.routeRepository = routeRepository;
        this.projectRepository = projectRepository;
        this.contextSnapshotRepository = contextSnapshotRepository;
        this.projectionRepository = projectionRepository;
        this.semanticTraceRecorder = semanticTraceRecorder;
        this.actionEligibilityGate = actionEligibilityGate;
        this.progressRecorder = progressRecorder;
        this.routeHistoryResolver = routeHistoryResolver;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * 为提交的回答执行回答循环。
     *
     * 输入校验与遗留编排器契约保持一致:所选选项必须属于正被回答的那个
     * 节点,自由文本仅在节点允许时接受,且至少要有一个有意义的输入。
     * 所有检查都在任何 Answer 落库之前执行。
     */
    public AnswerCycleResult submitAnswer(AgentRun run, UUID projectId,
                                          UUID selectedOptionId, String freeText) {
        return submitAnswer(run, projectId, selectedOptionId, freeText, null);
    }

    public AnswerCycleResult submitAnswer(AgentRun run, UUID projectId,
                                          UUID selectedOptionId, String freeText,
                                          AgentEvent.PersistenceIntent persistenceIntent) {
        return submitAnswer(run, projectId, selectedOptionId, freeText, persistenceIntent, null);
    }

    /**
     * 与上一个重载相同,但 run 可以指向一条显式路线
     * ({@code explicitRouteId != null})而不必是项目的 Active 路线。
     *
     * 这是多条路线得以独立运行的关键:即使路线 A 是 Active 路线,路线 B
     * 也能继续回答,因为 run 从自身解析目标,而不是重新读取唯一的 Active 指针。
     * tip/过期检查保持不变——只是改为对解析出的路线执行;当
     * {@code explicitRouteId == null} 时,行为与 Active 路线路径逐字节一致。
     */
    public AnswerCycleResult submitAnswer(AgentRun run, UUID projectId,
                                          UUID selectedOptionId, String freeText,
                                          AgentEvent.PersistenceIntent persistenceIntent,
                                          UUID explicitRouteId) {
        return submitAnswer(run, projectId,
                selectedOptionId == null ? List.of() : List.of(selectedOptionId),
                freeText, persistenceIntent, explicitRouteId);
    }

    /**
     * 多选提交:{@code selectedOptionIds} 是按用户顺序的完整选择集合。
     * 仅当被回答的节点带有 {@code allowMultiSelect} 时才允许多个条目;
     * 第一个条目在整个流水线中镜像遗留的单选语义。
     */
    public AnswerCycleResult submitAnswer(AgentRun run, UUID projectId,
                                          List<UUID> selectedOptionIds, String freeText,
                                          AgentEvent.PersistenceIntent persistenceIntent,
                                          UUID explicitRouteId) {
        Route route = resolveRunRoute(projectId, explicitRouteId);
        boolean explicitRoute = explicitRouteId != null;
        if (route.tipNodeId() == null) {
            throw new IllegalStateException(explicitRoute
                    ? "Route has no tip node: " + route.id()
                    : "Active route has no tip node");
        }
        Node tipNode = nodeService.getNode(route.tipNodeId())
                .orElseThrow(() -> new IllegalStateException(
                        "Active tip node not found: " + route.tipNodeId()));

        // 排队 run 在入队时记录了输入节点。若 worker 领取 run 之前图已推进,
        // 直接失败,而不是替用户回答一个与他当时所看节点不同的节点。
        if (run.inputNodeId() != null && !run.inputNodeId().equals(route.tipNodeId())) {
            throw new IllegalStateException(explicitRoute
                    ? "Answer target is not the tip of route " + route.id() + ": " + run.inputNodeId()
                    : "Answer target is no longer the active route tip: " + run.inputNodeId());
        }

        List<String> selectedOptions = validateSelectedOptions(tipNode, selectedOptionIds);
        String selectedOption = selectedOptions.isEmpty() ? null : selectedOptions.get(0);
        UUID selectedOptionId = selectedOptions.isEmpty() ? null
                : UUID.fromString(selectedOptions.get(0));
        String normalizedFreeText = normalizeFreeText(freeText);
        validateAnswerInput(tipNode, selectedOption, normalizedFreeText);

        String trace = "created";
        try {
            trace = appendTrace(trace, "context_built");
            ContextSnapshot snapshot = explicitRoute
                    ? buildAndValidateContextForRoute(run, projectId, route, trace)
                    : buildAndValidateContext(run, projectId, trace);

            // 在任何模型调用之前,先持久化不可变 Answer。
            // 所有权 fencing(原子协议):Answer 落库与带所有权条件的检查点
            // 写入(markPersistedAnswer)在同一事务——新执行器接管后,旧
            // 执行器的条件检查点落空(0 行),整个事务回滚,Answer 不落库。
            final String gateTrace = trace;
            Answer answer = transactionTemplate.execute(tx -> {
                // 所有权协议(第四轮):事务第一条语句对所有权行取 FOR SHARE
                // 并验证代次,锁保持到提交——接管的代次递增与本事务互斥,
                // 条件检查点不可能在过期快照下通过。已闩锁丢失立即拒绝。
                executionFence.lockOwnershipForWrite();
                Answer persisted = answerService.finalizeAnswerWithSelections(
                        projectId, route.id(), route.tipNodeId(),
                        selectedOptions, normalizedFreeText, "user");
                agentRunService.markPersistedAnswer(run.id(), persisted.id(), gateTrace);
                return persisted;
            });
            trace = appendTrace(trace, "persisted_answer");

            // 构造携带回答事件的请求信封。
            AgentEvent event = new AgentEvent(
                    "ANSWER_SUBMITTED", route.tipNodeId(),
                    selectedOptionId, normalizedFreeText, persistenceIntent);
            AgentRequestEnvelope envelope = snapshotBuilder.buildEnvelope(
                    run.id(), snapshot, event, new DecisionBudget(2));

            // STATE_UPDATE + DECISION + 策略评估 + 执行。
            return completeCycle(run, projectId, route, snapshot, envelope,
                    answer, selectedOptionId, normalizedFreeText, trace);

        } catch (RuntimeException ex) {
            failIfNotTerminal(run.id(), trace, ex);
            throw ex;
        }
    }

    /**
     * 恢复一个处理失败的既有回答。回答已持久化,因此绝不再次 finalize,
     * 不会创建第二个 Answer。
     *
     * 语义重放保证:恢复后的 DECISION 信封从不可变的已落库 Answer 重建,
     * 因此原始用户输入——ANSWER_SUBMITTED 事件类型、所选选项、自由文本、
     * 来源节点——与第一次尝试完全一致。恢复绝不退化成无上下文的 CONTINUE;
     * 已落库的补丁 checkpoint(若存在)被直接复用,不重跑 STATE_UPDATE。
     */
    public AnswerCycleResult resumeAnswer(AgentRun run, UUID projectId, UUID answerId) {
        return resumeAnswer(run, projectId, answerId, null);
    }

    public AnswerCycleResult resumeAnswer(AgentRun run, UUID projectId, UUID answerId,
                                          AgentEvent.PersistenceIntent persistenceIntent) {
        return resumeAnswer(run, projectId, answerId, persistenceIntent, null);
    }

    /** 使用显式路线恢复(模式说明见 {@link #submitAnswer})。 */
    public AnswerCycleResult resumeAnswer(AgentRun run, UUID projectId, UUID answerId,
                                          AgentEvent.PersistenceIntent persistenceIntent,
                                          UUID explicitRouteId) {
        Answer answer = answerService.getAnswer(answerId)
                .orElseThrow(() -> new IllegalArgumentException("Answer not found: " + answerId));
        if (!answer.projectId().equals(projectId)) {
            throw new IllegalArgumentException(
                    "Answer does not belong to project: " + projectId);
        }

        Route route = resolveRunRoute(projectId, explicitRouteId);
        boolean explicitRoute = explicitRouteId != null;
        if (!answer.routeId().equals(route.id())) {
            throw new IllegalStateException(explicitRoute
                    ? "Answer does not belong to route " + route.id()
                    : "Answer does not belong to active route");
        }
        if (route.tipNodeId() == null
                || !routeHistoryResolver.resolveLineage(route.tipNodeId()).contains(answer.nodeId())) {
            throw new IllegalStateException(explicitRoute
                    ? "Answer node is not part of route " + route.id()
                    : "Answer node is not part of the active route history");
        }

        // 非 tip 回答只能在它的 checkpoint 边界上修复。
        // 绝不向当前 tip 重放其后的 DECISION,也绝不要求与本次
        // "仅 checkpoint 恢复"无关的遗留事后状态投影。
        if (!answer.nodeId().equals(route.tipNodeId())) {
            return recoverHistoricalAnswer(run, projectId, route, answer, persistenceIntent);
        }

        failIfLegacyReplay(answer.id());

        String trace = "created";
        try {
            trace = appendTrace(trace, "context_built");
            final String traceAfterBuild = trace;
            // 冻结输入重放:修复重跑 STATE_UPDATE 时,复用原始尝试的"回答前"
            // 快照,使模型输入不会随工作区的实时变化而漂移。只有原始快照
            // 无法找到的尝试才构建全新上下文。
            ContextSnapshot snapshot = resolveOriginalPreAnswerSnapshot(projectId, answer)
                    .map(original -> attachSnapshot(run, original, traceAfterBuild))
                    .orElseGet(() -> explicitRoute
                            ? buildAndValidateContextForRoute(run, projectId, route, traceAfterBuild)
                            : buildAndValidateContext(run, projectId, traceAfterBuild));

            trace = appendTrace(trace, "persisted_answer");
            agentRunService.markPersistedAnswer(run.id(), answer.id(), trace);

            // 从已落库的 Answer 重建原始提交语义——而不是依据调用方新输入,
            // 也绝不能退化为一个裸 CONTINUE。
            UUID selectedOptionId = answer.selectedOptionId() == null
                    ? null : UUID.fromString(answer.selectedOptionId());
            String freeText = answer.freeText();
            AgentEvent event = new AgentEvent(
                    "ANSWER_SUBMITTED", answer.nodeId(), selectedOptionId, freeText,
                    persistenceIntent);
            AgentRequestEnvelope envelope = snapshotBuilder.buildEnvelope(
                    run.id(), snapshot, event, new DecisionBudget(2));

            return completeCycle(run, projectId, route, snapshot, envelope,
                    answer, selectedOptionId, freeText, trace);

        } catch (RuntimeException ex) {
            failIfNotTerminal(run.id(), trace, ex);
            throw ex;
        }
    }

    /**
     * 对已在所属路线上成为历史的回答做兼容性恢复。只执行缺失的 STATE_UPDATE
     * checkpoint。若补丁已落库,本操作是幂等空操作;无论哪种情况,都不允许
     * 执行 DECISION、创建节点或变更路线 tip。
     */
    private AnswerCycleResult recoverHistoricalAnswer(
            AgentRun run, UUID projectId, Route route, Answer answer,
            AgentEvent.PersistenceIntent persistenceIntent) {
        String trace = "created";
        try {
            trace = appendTrace(trace, "historical_recovery");
            agentRunService.markPersistedAnswer(run.id(), answer.id(), trace);

            AnswerPatch patch = answerPatchService.findBySourceAnswerId(answer.id()).orElse(null);
            if (patch == null) {
                ContextSnapshot original = resolveOriginalPreAnswerSnapshot(projectId, answer)
                        .orElseThrow(() -> new LegacyFrozenInputUnavailableException(
                                "LEGACY_FROZEN_INPUT_UNAVAILABLE: historical answer "
                                        + answer.id() + " has no original pre-answer ContextSnapshot; "
                                        + "checkpoint-only recovery cannot use current route context"));
                trace = appendTrace(trace, "context_reused:" + original.id());
                attachSnapshot(run, original, trace);
                UUID selectedOptionId = answer.selectedOptionId() == null
                        ? null : UUID.fromString(answer.selectedOptionId());
                AgentEvent event = new AgentEvent(
                        "ANSWER_SUBMITTED", answer.nodeId(), selectedOptionId,
                        answer.freeText(), persistenceIntent);
                AgentRequestEnvelope envelope = snapshotBuilder.buildEnvelope(
                        run.id(), original, event, new DecisionBudget(1));
                patch = runStateUpdate(run, projectId, route, envelope, answer, trace);
                trace = appendTrace(trace, "persisted_patch");
            } else {
                trace = appendTrace(trace, "reused_persisted_patch:" + patch.id());
                validatePatchSources(patch, route, answer);
                agentRunService.markPersistedAnswerPatch(run.id(), patch.id(), trace);
                eventService.append(run.id(), AgentRunPhase.STATE_UPDATED,
                        "STATE_UPDATE_SKIPPED", Map.of("reason", "patch_exists"));
            }

            trace = appendTrace(trace, "historical_recovery_complete");
            agentRunService.complete(run.id(), AgentRunStatus.COMPLETED, trace);
            eventService.append(run.id(), AgentRunPhase.COMPLETED, "HISTORICAL_ANSWER_RECOVERED",
                    Map.of("answerId", answer.id().toString(),
                           "patchId", patch.id().toString(),
                           "graphMutation", "none"));
            eventService.append(run.id(), AgentRunPhase.COMPLETED, "RUN_COMPLETED",
                    Map.of("producedAnswerId", answer.id().toString(),
                           "producedPatchId", patch.id().toString(),
                           "historicalRecovery", true));
            return new AnswerCycleResult(run.id(), answer.id(), patch.id(), null,
                    "historical_recovery");
        } catch (RuntimeException ex) {
            failIfNotTerminal(run.id(), trace, ex);
            throw ex;
        }
    }

    /**
     * 回答之后的共享处理:STATE_UPDATE → 补丁 checkpoint → 事后状态 DECISION
     * 快照 → DECISION → 策略 → 执行。消除 submitAnswer 与 resumeAnswer 之间的重复。
     */
    private AnswerCycleResult completeCycle(AgentRun run, UUID projectId,
                                            Route route, ContextSnapshot snapshot,
                                            AgentRequestEnvelope envelope,
                                            Answer answer,
                                            UUID selectedOptionId, String freeText,
                                            String trace) {
        // 检查是否已有补丁(修复门)。
        AnswerPatch patch = answerPatchService.findBySourceAnswerId(answer.id()).orElse(null);
        boolean resumeWithCheckpoint = patch != null;

        // 第 1 次调用:STATE_UPDATE(补丁已存在时跳过)。
        if (patch == null) {
            patch = runStateUpdate(run, projectId, route, envelope, answer, trace);
            trace = appendTrace(trace, "persisted_patch");
        } else {
            trace = appendTrace(trace, "reused_persisted_patch:" + patch.id());
            agentRunService.markPersistedAnswerPatch(run.id(), patch.id(), trace);
            eventService.append(run.id(), AgentRunPhase.STATE_UPDATED,
                    "STATE_UPDATE_SKIPPED", Map.of("reason", "patch_exists"));
        }

        // STATE_UPDATE 是持久化 checkpoint。DECISION 必须读取该 checkpoint 之后
        // 的状态,而不是第一次模型调用所用的"回答前"快照。修复恢复时,复用
        // 原始尝试的事后(post-state)快照(以及其冻结的模型输入);只有首次
        // 尝试——或前驱从未走到 DECISION 调用的修复——才构建全新的事后快照。
        // 重建时必须针对本 run 的确切路线(绝不是"当前恰好活跃的路线"),使新
        // Answer/Patch/有效 claims 具备因果可见性,同时路线隔离保持 fail-closed。
        ContextSnapshot decisionSnapshot;
        AgentRequestEnvelope decisionEnvelope;
        try {
            decisionSnapshot = resolvePostStateSnapshot(
                            projectId, route, answer.id(), run.id(), resumeWithCheckpoint)
                    .orElseGet(() -> contextBuilder.buildForRoute(
                            projectId, route.id(), route.tipNodeId(), run.id(),
                            ContextOperationType.NORMAL));
            decisionEnvelope = actionEligibilityGate.prepareDecisionRequest(
                    snapshotBuilder.buildEnvelope(
                            run.id(), decisionSnapshot, envelope.event(), envelope.decisionBudget()));
        } catch (RuntimeException ex) {
            semanticTraceRecorder.captureFailure(run.id(),
                    "DECISION_INPUT_PROJECTION", ex);
            throw ex;
        }
        semanticTraceRecorder.capturePostState(run.id(), decisionEnvelope.snapshot());

        // 第 2 次调用:DECISION,走共享执行核心。策略/过期检查/执行/终态化
        // 这一段与问题起草周期是同一条 fail-closed 链;只有上面的 DECISION
        // 输入准备是回答特有的。
        trace = appendTrace(trace, "deciding");
        ActionExecutionContext execContext = new ActionExecutionContext(
                run.id(), projectId, route.id(), decisionSnapshot.id(),
                route.tipNodeId(), selectedOptionId, freeText);
        DecisionExecutionService.DecisionExecutionResult executed =
                decisionExecution.execute(decisionSnapshot, decisionEnvelope,
                        execContext, trace, "\n",
                        Map.of(
                                "snapshotId", decisionSnapshot.id().toString(),
                                "contextHash", decisionSnapshot.contextHash()));

        return new AnswerCycleResult(run.id(), answer.id(), patch.id(),
                executed.producedNodeId(), executed.outcome());
    }

    /**
     * 执行 STATE_UPDATE:claims 落锚、校验并持久化补丁 checkpoint,
     * 返回已落库的补丁。
     */
    private AnswerPatch runStateUpdate(AgentRun run, UUID projectId,
                                       Route route, AgentRequestEnvelope envelope,
                                       Answer answer, String trace) {
        trace = appendTrace(trace, "state_updating");
        eventService.append(run.id(), AgentRunPhase.STATE_UPDATING,
                "STATE_UPDATE_STARTED", Map.of());

        semanticTraceRecorder.captureStateUpdateInput(envelope);
        AgentResponseEnvelope stateUpdateResponse;
        try {
            stateUpdateResponse = decisionEngine.runStateUpdate(envelope);
            AgentBrainResponseValidator.validateStateUpdate(envelope, stateUpdateResponse);
            semanticTraceRecorder.captureStateUpdateOutput(stateUpdateResponse);
        } catch (RuntimeException ex) {
            semanticTraceRecorder.captureFailure(run.id(), "STATE_UPDATE_OUTPUT", ex);
            throw ex;
        }

        try {
            eventService.append(run.id(), AgentRunPhase.STATE_UPDATED,
                    "STATE_UPDATE_COMPLETED", Map.of(
                            "claimCount", stateUpdateResponse.stateUpdate() == null
                                    ? 0 : stateUpdateResponse.stateUpdate().claims().size()));

            List<ProposedClaim> proposedClaims = stateUpdateResponse.stateUpdate().claims();
            progressRecorder.noteWithItems(run.id(), AgentRunPhase.STATE_UPDATED,
                    "需求要点整理完成，共 " + proposedClaims.size() + " 条",
                    proposedClaims.stream().map(ProposedClaim::text).toList());

            List<Claim> groundedClaims = groundClaims(
                    stateUpdateResponse.stateUpdate().claims(),
                    answer.nodeId(), answer.id());
            validateClaimSources(groundedClaims, answer);

            ReflectionResult patchReflection = patchReflectionGate.validate(
                    new com.specagent.agent.decision.AnswerPatchDraft(groundedClaims));
            agentRunService.markReflected(run.id(), trace);

            if (!patchReflection.accepted()) {
                agentRunService.fail(run.id(),
                        appendTrace(trace, "failed:patch_reflection_rejected"));
                throw new ModelContractException(
                        "Patch reflection rejected: " + patchReflection.errors());
            }

            // 所有权 fencing(原子协议):补丁落库与带所有权条件的检查点
            // 写入在同一事务——丢锁执行器的检查点落空即整体回滚,补丁不落库。
            final String patchTrace = trace;
            AnswerPatch patch = transactionTemplate.execute(tx -> {
                executionFence.lockOwnershipForWrite();
                AnswerPatch persisted = answerPatchService.saveOrReuse(
                        projectId, route.id(), answer.nodeId(), answer.id(),
                        groundedClaims, run.id());
                validatePatchSources(persisted, route, answer);
                agentRunService.markPersistedAnswerPatch(run.id(), persisted.id(), patchTrace);
                return persisted;
            });
            return patch;
        } catch (RuntimeException ex) {
            semanticTraceRecorder.captureFailure(run.id(), "STATE_APPLICATION", ex);
            throw ex;
        }
    }

    private List<Claim> groundClaims(List<ProposedClaim> proposedClaims,
                                     UUID sourceNodeId, UUID sourceAnswerId) {
        List<Claim> grounded = new ArrayList<>();
        for (ProposedClaim pc : proposedClaims) {
            ClaimKind kind = ClaimKind.fromCode(pc.kind());
            ClaimStatus status = ClaimStatus.fromCode(pc.status());
            if (status == ClaimStatus.CONFIRMED) {
                grounded.add(new Claim(null, kind, pc.text(), status,
                        pc.confidence(), sourceNodeId, sourceAnswerId));
            } else {
                grounded.add(new Claim(null, kind, pc.text(), status,
                        pc.confidence(), null, null));
            }
        }
        return grounded;
    }

    /**
     * 已确认(CONFIRMED)的 Claim 是关于这条不可变 Answer 的证据。因此其来源
     * 身份必须始终锚定在 Answer 自己的节点上——即使恢复发生在路线 tip 已经
     * 前进之后。
     */
    private void validateClaimSources(List<Claim> claims, Answer answer) {
        for (Claim claim : claims) {
            boolean hasSourceNode = claim.sourceNodeId() != null;
            boolean hasSourceAnswer = claim.sourceAnswerId() != null;
            if (hasSourceNode != hasSourceAnswer) {
                throw new ModelContractException(
                        "Claim provenance must contain both sourceNodeId and sourceAnswerId");
            }
            if (hasSourceAnswer && !answer.id().equals(claim.sourceAnswerId())) {
                throw new ModelContractException(
                        "Claim sourceAnswerId does not match the recovered Answer");
            }
            if (hasSourceNode && !answer.nodeId().equals(claim.sourceNodeId())) {
                throw new ModelContractException(
                        "Claim sourceNodeId does not match the recovered Answer node");
            }
        }
    }

    /** 已落库的 Patch 必须携带与其 Answer 相同的来源身份。 */
    private void validatePatchSources(AnswerPatch patch, Route route, Answer answer) {
        if (!answer.id().equals(patch.sourceAnswerId())
                || !answer.nodeId().equals(patch.sourceNodeId())
                || !route.id().equals(patch.routeId())) {
            throw new IllegalStateException(
                    "AnswerPatch source identity does not match its Answer and route");
        }
        validateClaimSources(patch.claims(), answer);
    }

    /**
     * 校验客户端选择的选项 id 确实属于被回答的那个节点。客户端只能引用该
     * 节点此前返回的、runtime 持有的既有选项 id;来自其他节点、兄弟路线或
     * 随意编造的 id,在任何回答落库之前就会被拒绝。
     */
    private String validateSelectedOption(Node tipNode, UUID selectedOptionId) {
        if (selectedOptionId == null) {
            return null;
        }
        return tipNode.options().stream()
                .filter(option -> option.id().equals(selectedOptionId))
                .findFirst()
                .map(option -> option.id().toString())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Selected option id does not belong to the active node"));
    }

    /**
     * 校验客户端的完整选择集合是否属于被回答的那个节点。多选仅在多选问题
     * ({@code allowMultiSelect})上合法;重复项被折叠但保留用户顺序,且每个
     * id 都必须是该节点持有的选项。
     */
    private List<String> validateSelectedOptions(Node tipNode, List<UUID> selectedOptionIds) {
        if (selectedOptionIds == null || selectedOptionIds.isEmpty()) {
            return List.of();
        }
        if (selectedOptionIds.size() > 1 && !tipNode.allowMultiSelect()) {
            throw new IllegalArgumentException(
                    "This node does not allow multiple selected options");
        }
        List<String> result = new ArrayList<>();
        for (UUID optionId : selectedOptionIds) {
            String matched = validateSelectedOption(tipNode, optionId);
            if (matched != null && !result.contains(matched)) {
                result.add(matched);
            }
        }
        return result;
    }

    private String normalizeFreeText(String freeText) {
        return (freeText == null || freeText.isBlank()) ? null : freeText;
    }

    /**
     * 强制执行回答输入策略:至少需要一个有意义的输入(有效选项或非空自由
     * 文本);当节点不允许自由作答时,非空自由文本会被拒绝。
     */
    private void validateAnswerInput(Node tipNode, String selectedOption, String freeText) {
        if (selectedOption == null && freeText == null) {
            throw new IllegalArgumentException("Answer requires a selected option or free text");
        }
        if (freeText != null && !tipNode.allowFreeAnswer()) {
            throw new IllegalArgumentException("This node does not allow free-form answers");
        }
    }

    private Route loadActiveRoute(UUID projectId) {
        com.specagent.workspace.project.Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.activeRouteId() == null) {
            throw new IllegalStateException("Project has no active route: " + projectId);
        }
        return routeRepository.findById(project.activeRouteId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Active route not found: " + project.activeRouteId()));
    }

    /**
     * 回答循环写入的目标路线。没有显式路线时是项目 Active 路线(行为不变,
     * 指针变动时仍然 fail-closed);有显式路线时,该路线必须属于本项目且
     * 处于 OPEN 状态。
     */
    private Route resolveRunRoute(UUID projectId, UUID explicitRouteId) {
        if (explicitRouteId == null) {
            return loadActiveRoute(projectId);
        }
        Route route = routeRepository.findById(explicitRouteId)
                .orElseThrow(() -> new IllegalArgumentException("Route not found: " + explicitRouteId));
        if (!route.projectId().equals(projectId)) {
            throw new IllegalArgumentException(
                    "Route " + explicitRouteId + " does not belong to project " + projectId);
        }
        if (route.lifecycleStatus() != com.specagent.workspace.route.RouteLifecycleStatus.OPEN) {
            throw new IllegalStateException(
                    "Route is not open: " + explicitRouteId
                            + " is " + route.lifecycleStatus().code());
        }
        return route;
    }

    private ContextSnapshot buildAndValidateContext(AgentRun run, UUID projectId, String trace) {
        ContextSnapshot snapshot = contextBuilder.buildFromActiveRoute(
                projectId, run.id(), ContextOperationType.NORMAL);
        return attachSnapshot(run, snapshot, trace);
    }

    /**
     * 显式路线上下文:从 run 自己的路线构建,而不是 Active 指针,使一条
     * 独立运行的链路读取的是自己的谱系。
     */
    private ContextSnapshot buildAndValidateContextForRoute(AgentRun run, UUID projectId,
                                                            Route route, String trace) {
        ContextSnapshot snapshot = contextBuilder.buildForRoute(
                projectId, route.id(), route.tipNodeId(), run.id(), ContextOperationType.NORMAL);
        return attachSnapshot(run, snapshot, trace);
    }

    private ContextSnapshot attachSnapshot(AgentRun run, ContextSnapshot snapshot, String trace) {
        agentRunService.attachContext(run.id(), snapshot.id(), trace);
        eventService.append(run.id(), AgentRunPhase.SNAPSHOT_BUILT,
                "SNAPSHOT_BUILT", Map.of(
                        "snapshotId", snapshot.id().toString(),
                        "contextHash", snapshot.contextHash()));
        return snapshot;
    }

    /**
     * STATE_UPDATE 重跑的冻结输入重放:通过已落库回答的第一个产出 run,找到
     * 原始尝试的"回答前"快照。仅对"仍是 tip"的兼容路径,找不到快照才返回空
     * ——全新上下文在那里是明确的遗留回退;历史的"仅 checkpoint 恢复"会拒绝
     * 这个空结果。发现的不一致快照一律 fail-closed,绝不静默重建。
     */
    private java.util.Optional<ContextSnapshot> resolveOriginalPreAnswerSnapshot(
            UUID projectId, Answer answer) {
        java.util.Optional<AgentRun> originalRun = agentRunService.findByProducedAnswerId(answer.id()).stream()
                .filter(r -> r.contextSnapshotId() != null)
                .findFirst();
        if (originalRun.isPresent()) {
            UUID preId = originalRun.get().contextSnapshotId();
            boolean modelCalled = eventService.findByRunId(originalRun.get().id()).stream()
                    .anyMatch(e -> e.eventType().startsWith("STATE_"));
            if (modelCalled && projectionRepository.findBySnapshotId(preId).isEmpty()) {
                throw new LegacyFrozenInputUnavailableException(
                        "LEGACY_FROZEN_INPUT_UNAVAILABLE: pre-answer frozen projection absent for snapshot "
                                + preId + " -- legacy STATE_UPDATE for answer " + answer.id() + " cannot be replayed");
            }
        }
        return agentRunService.findByProducedAnswerId(answer.id()).stream()
                .map(AgentRun::contextSnapshotId)
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .flatMap(contextSnapshotRepository::findById)
                .map(snapshot -> {
                    if (!snapshot.projectId().equals(projectId)
                            || !snapshot.routeId().equals(answer.routeId())
                            || snapshot.operationType() != ContextOperationType.NORMAL
                            || !answer.nodeId().equals(snapshot.tipNodeId())) {
                        throw new IllegalStateException(
                                "Original pre-answer snapshot does not match the answer context: "
                                        + snapshot.id());
                    }
                    return snapshot;
                });
    }

    /**
     * 修复时的冻结输入重放守卫:遗留重放要求此前尝试冻结过 DECISION 投影。
     * 每个 DECISION 信封都在 DECISION_STARTED 事件里携带其快照身份;若存在
     * DECISION_STARTED 事件却没有可用的冻结投影(或事件不含快照身份),
     * 语义重放不可用,直接抛出异常,而不是用新快照伪造重放。
     */
    private void failIfLegacyReplay(UUID answerId) {
        boolean hasPatch = answerPatchService.findBySourceAnswerId(answerId).isPresent();
        if (!hasPatch) {
            return;
        }
        java.util.List<AgentRun> attempts = agentRunService.findByProducedAnswerId(answerId);
        boolean hasDecisionStarted = false;
        java.util.List<UUID> snapshotIds = new java.util.ArrayList<>();
        for (AgentRun attempt : attempts) {
            for (com.specagent.agent.runevent.AgentRunEvent event : eventService.findByRunId(attempt.id())) {
                if ("DECISION_STARTED".equals(event.eventType())) {
                    hasDecisionStarted = true;
                    Object sid = event.payload().get("snapshotId");
                    if (sid != null) {
                        try {
                            snapshotIds.add(UUID.fromString(String.valueOf(sid)));
                        } catch (Exception ignored) {}
                    }
                }
            }
        }
        if (!hasDecisionStarted) {
            return;
        }
        if (snapshotIds.isEmpty()) {
            throw new LegacyFrozenInputUnavailableException(
                    "LEGACY_FROZEN_INPUT_UNAVAILABLE: legacy DECISION for answer "
                            + answerId + " has no frozen projection and carries no snapshot identity -- "
                            + "semantic replay is unavailable; retry from a fresh snapshot instead");
        }
        for (UUID sid : snapshotIds) {
            var frozen = projectionRepository.findBySnapshotId(sid);
            if (frozen.isEmpty()) {
                throw new LegacyFrozenInputUnavailableException(
                        "LEGACY_FROZEN_INPUT_UNAVAILABLE: post-state frozen projection absent for snapshot "
                                + sid + " -- legacy replay for answer " + answerId + " cannot be reproduced");
            }
        }
    }

    private java.util.Optional<ContextSnapshot> resolvePostStateSnapshot(
            UUID projectId, Route route, UUID answerId, UUID currentRunId,
            boolean resumeWithCheckpoint) {
        if (!resumeWithCheckpoint) {
            // 首次尝试:不存在需要保留的早期 DECISION 输入。
            return java.util.Optional.empty();
        }
        java.util.List<UUID> decisionSnapshotIds = new java.util.ArrayList<>();
        for (AgentRun attempt : agentRunService.findByProducedAnswerId(answerId)) {
            if (attempt.id().equals(currentRunId)) {
                continue;
            }
            for (AgentRunEvent event : eventService.findByRunId(attempt.id())) {
                Object snapshotId = event.payload().get("snapshotId");
                if ("DECISION_STARTED".equals(event.eventType()) && snapshotId != null) {
                    decisionSnapshotIds.add(UUID.fromString(String.valueOf(snapshotId)));
                }
            }
        }
        if (decisionSnapshotIds.isEmpty()) {
            return java.util.Optional.empty();
        }
        UUID latest = decisionSnapshotIds.get(decisionSnapshotIds.size() - 1);
        ContextSnapshot snapshot = contextSnapshotRepository.findById(latest)
                .orElseThrow(() -> new IllegalStateException(
                        "Repaired DECISION snapshot is missing: " + latest));
        if (!snapshot.projectId().equals(projectId)
                || !snapshot.routeId().equals(route.id())
                || snapshot.operationType() != ContextOperationType.NORMAL
                || !snapshot.tipNodeId().equals(route.tipNodeId())) {
            throw new IllegalStateException(
                    "Repaired DECISION snapshot does not match the answer route context: "
                            + latest);
        }
        return java.util.Optional.of(snapshot);
    }

    private void failIfNotTerminal(UUID runId, String trace, RuntimeException ex) {
        AgentRun latest = agentRunService.getRun(runId).orElse(null);
        if (latest != null && latest.status() != AgentRunStatus.FAILED
                && latest.status() != AgentRunStatus.COMPLETED) {
            String persisted = latest.trace();
            String base = (persisted == null || persisted.isBlank()) ? trace : persisted;
            String reason = RunFailureReasons.reasonCode(ex);
            // 状态转换与 RUN_FAILED 事件由失败服务在同一事务内原子提交:
            // 状态未生效(所有权拒绝/已终态)时不会有"本次失败已发生"的事件。
            agentRunFailureService.fail(runId, appendTrace(base, "failed:" + reason), ex);
        }
    }

    private String appendTrace(String trace, String step) {
        return trace == null || trace.isBlank() ? step : trace + "\n" + step;
    }
}
