package com.specagent.eval;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.policy.AgentProposal;
import com.specagent.agent.policy.AgentProposalService;
import com.specagent.agent.policy.ProposalStatus;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerRepository;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.context.ContextSnapshotRepository;
import com.specagent.workspace.graph.GraphCommandService;
import com.specagent.model.contract.ModelInferenceGateway;
import com.specagent.workspace.node.KnowledgeStatus;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
import com.specagent.workspace.route.RouteService;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.agent.trace.SemanticTrace;
import com.specagent.agent.trace.SemanticTraceRecorder;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:ScenarioRunner.java
 *
 * 用途:P2 评测场景运行器,单个评测尝试的真实执行链路:
 * 声明式 Scenario
 *   → 规范的 Java 运行时搭建(项目、图、能力、资源)
 *   → 生产回答周期(RunService + RunWorker + AnswerCycleService)
 *   → STATE_UPDATE → Java 校验/应用 → 更新后的 ContextSnapshot
 *   → DECISION → Java 策略/执行
 *   → 观察(只读规范状态)→ Layer A / B-fast 校验
 *   → 可写 JSONL 的 ObservationEnvelope
 *
 * 运行器只做观察、编排和断言。图、路由、节点、Answer 持久化、patch
 * 生命周期、快照、授权、确认、能力策略和规范变更全部仍归 Java 运行时所有。
 * 脚本化 B-fast Brain 只在边界处替代 Brain 的结构化输出;生产调用计数读取
 * 脚本引擎记录的阶段。
 *
 * 协作:消费 {@link ScenarioDefinition} / {@link VariantSpec},产出
 * {@link ObservationEnvelope};被 LayerA / LayerBFast 消费的
 * {@link AttemptContext} 也在这里构造。
 */
@Component
public class ScenarioRunner {

    private final ProjectService projectService;
    private final NodeService nodeService;
    private final RouteService routeService;
    private final RouteRepository routeRepository;
    private final GraphCommandService graphCommandService;
    private final RunService runService;
    private final RunWorker runWorker;
    private final AgentRunService agentRunService;
    private final AgentRunEventService eventService;
    private final AnswerService answerService;
    private final AnswerRepository answerRepository;
    private final AnswerPatchService answerPatchService;
    private final ContextSnapshotRepository contextSnapshotRepository;
    private final AgentProposalService proposalService;
    private final ObjectProvider<BrainScriptInstaller> brainScripts;
    private final ObjectProvider<AgentDecisionEngine> decisionEngines;
    private final ObjectProvider<ModelInferenceGateway> inferenceGateways;
    private final SemanticTraceRecorder semanticTraceRecorder;

    private volatile UUID lastProjectId;

    public ScenarioRunner(ProjectService projectService,
                          NodeService nodeService,
                          RouteService routeService,
                          RouteRepository routeRepository,
                          GraphCommandService graphCommandService,
                          RunService runService,
                          RunWorker runWorker,
                          AgentRunService agentRunService,
                          AgentRunEventService eventService,
                          AnswerService answerService,
                          AnswerRepository answerRepository,
                          AnswerPatchService answerPatchService,
                          ContextSnapshotRepository contextSnapshotRepository,
                          AgentProposalService proposalService,
                          ObjectProvider<BrainScriptInstaller> brainScripts,
                          ObjectProvider<AgentDecisionEngine> decisionEngines,
                          ObjectProvider<ModelInferenceGateway> inferenceGateways,
                          SemanticTraceRecorder semanticTraceRecorder) {
        this.projectService = projectService;
        this.nodeService = nodeService;
        this.routeService = routeService;
        this.routeRepository = routeRepository;
        this.graphCommandService = graphCommandService;
        this.runService = runService;
        this.runWorker = runWorker;
        this.agentRunService = agentRunService;
        this.eventService = eventService;
        this.answerService = answerService;
        this.answerRepository = answerRepository;
        this.answerPatchService = answerPatchService;
        this.contextSnapshotRepository = contextSnapshotRepository;
        this.proposalService = proposalService;
        this.brainScripts = brainScripts;
        this.decisionEngines = decisionEngines;
        this.inferenceGateways = inferenceGateways;
        this.semanticTraceRecorder = semanticTraceRecorder;
    }

    public UUID lastProjectId() {
        return lastProjectId;
    }

    /** 端到端运行一个场景变体并返回其观察记录。 */
    public ObservationEnvelope run(ScenarioDefinition scenario, VariantSpec variant) {
        return run(scenario, variant, EvaluationProfile.B_FAST);
    }

    /**
     * P2 阶段二——live 行为基线入口。
     *
     * 执行完全相同的场景契约(同样的规范 Java 运行时搭建、同样的 Layer A
     * 不变量、同样的 Layer B 期望、同样的调用预算),但由生产 Brain 装配来
     * 回答 STATE_UPDATE + DECISION,而不是安装脚本化 Brain 输出。场景的
     * {@code given.brainScript} 被忽略——绝不会被当作期望复制——因此真正
     * 收敛到可接受动作的 live Brain 依然通过,发散的则按同一契约失败。
     *
     * 要求没有任何 {@code BrainScriptInstaller} bean 处于激活状态:一旦
     * 装了脚本化 Brain,运行器拒绝以 live 方式运行,live 观察绝不可能悄悄
     * 来自脚本输出。
     *
     * @param repetitionSeed 本次重复的记录种子(N=3 次同一变体的运行只在这个
     *     种子上不同,语义完全一致)
     */
    public ObservationEnvelope runLive(ScenarioDefinition scenario, VariantSpec variant,
                                       long repetitionSeed) {
        requireLiveWiring();
        return run(scenario, variant, EvaluationProfile.LIVE_PROVIDER, repetitionSeed);
    }

    /** 在启动 B-live 尝试前校验具体的 Spring bean 身份。 */
    LiveExecutionGuard.Evidence requireLiveWiring() {
        return LiveExecutionGuard.requireRemoteProvider(
                decisionEngines.getIfAvailable(),
                inferenceGateways.getIfAvailable(),
                brainScripts.getIfAvailable());
    }

    private ObservationEnvelope run(ScenarioDefinition scenario, VariantSpec variant,
                                    EvaluationProfile profile) {
        scenario.validate();
        BrainScriptInstaller brain = brainScripts.getIfAvailable();
        if (brain == null) {
            throw new IllegalStateException(
                    "ScenarioRunner requires a BrainScriptInstaller (eval tests only)");
        }
        long startNanos = System.nanoTime();
        resetProbeCapabilities();
        installCapabilitySuccessFlags(scenario);
        brain.resetScripts();
        brain.installScript(scenario.given().brainScript(),
                scenario.scenarioId(), variant.seed(), variant.paraphraseIndex());
        return runAfterSetup(scenario, variant, profile, variant.seed(), brain, startNanos);
    }

    private ObservationEnvelope run(ScenarioDefinition scenario, VariantSpec variant,
                                    EvaluationProfile profile, long repetitionSeed) {
        scenario.validate();
        long startNanos = System.nanoTime();
        resetProbeCapabilities();
        installCapabilitySuccessFlags(scenario);
        return runAfterSetup(scenario, variant, profile, repetitionSeed, null, startNanos);
    }

    /**
     * Shared attempt body after Brain setup: canonical Java runtime setup,
     * production answer cycle, canonical-state observation, contract assembly.
     * The only difference between profiles is what answers STATE_UPDATE +
     * DECISION at the boundary (scripted vs production wiring) — setup,
     * observation, Layer A, Layer B and the call budget are shared.
     */
    private ObservationEnvelope runAfterSetup(ScenarioDefinition scenario, VariantSpec variant,
                                              EvaluationProfile profile, long observationSeed,
                                              BrainScriptInstaller brain, long startNanos) {

        Project project = projectService.createProject(renderTitle(scenario, variant));
        lastProjectId = project.id();
        Setup setup = buildGraph(scenario, variant, project);
        StateSummary preState = summarize(project.id());
        int preRelations = countRelations(project.id());
        List<UUID> preAnswerIds = answerIds(project.id());

        String failureDetail = null;
        UUID runId = null;
        try {
            runId = driveUserEvent(scenario, variant, project, setup).runId();
        } catch (DriveFailure failure) {
            failureDetail = failure.detail();
            runId = failure.runId();
        } catch (RuntimeException ex) {
            failureDetail = ex.getClass().getSimpleName() + ": " + ex.getMessage();
        }

        // 失败也要后置观察:fail-closed 意味着规范状态必须显示没有发生
        // 意料之外的变更。
        long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
        if (brain == null) {
            AttemptContext context = observeLive(
                    scenario, variant, project, runId, preState, preRelations, preAnswerIds,
                    failureDetail, latencyMs);
            return assemble(scenario, variant, context, profile, observationSeed);
        }
        AttemptContext context = observe(
                scenario, variant, project, runId, preState, preRelations, preAnswerIds,
                brain, failureDetail, latencyMs);
        return assemble(scenario, variant, context, profile, observationSeed);
    }

    // -- 搭建(setup)-----------------------------------------------------------

    private String renderTitle(ScenarioDefinition scenario, VariantSpec variant) {
        String label = "eval-" + scenario.scenarioId() + "-" + variant.variantId()
                + "-" + scenario.given().projectTitleSeed() + "-" + variant.seed();
        if (variant.routeVariation() != null && !variant.routeVariation().isBlank()) {
            label += "-" + variant.routeVariation();
        }
        return label;
    }

    /** 按声明搭建初始图;键把步骤引用映射到节点/路由。 */
    private Setup buildGraph(ScenarioDefinition scenario, VariantSpec variant, Project project) {
        Setup setup = new Setup();
        Route activeRoute = routeRepository.findById(project.activeRouteId()).orElseThrow();
        setup.activeRoute = activeRoute;

        List<Map.Entry<String, GraphStep>> ordered = new ArrayList<>();
        List<GraphStep> declared = scenario.given().initialGraph();
        for (int index = 0; index < declared.size(); index++) {
            ordered.add(Map.entry("step-" + index, declared.get(index)));
        }
        if (variant.shuffleIrrelevantContext()) {
            // 只重排无依赖关系的上下文:步骤按拓扑序执行(依赖优先),
            // 以 shuffle 键做同层排序的平局裁断,这样引用不会悬空,
            // 而无关内容的排列顺序仍能跨变体变化。
            ordered = topologicalWithShuffleTieBreak(ordered, variant.seed());
        }
        for (Map.Entry<String, GraphStep> entry : ordered) {
            String ref = entry.getKey();
            GraphStep step = entry.getValue();
            if (step instanceof GraphStep.CreateRootQuestion root) {
                Node node = nodeService.createRootNode(project.id(), setup.activeRoute.id(),
                        GraphStep.renderText(scenario.scenarioId(), root.questionSeed(),
                                variant.paraphraseIndex(), Map.of()),
                        "eval purpose", List.of(), root.allowFreeAnswer());
                setup.stepNodes.put(ref, node.id());
                setup.activeRoute = routeRepository.findById(setup.activeRoute.id()).orElseThrow();
            } else if (step instanceof GraphStep.CreateChildQuestion child) {
                UUID parentId = requireStepNode(setup, child.parentStepRef());
                Node node = nodeService.createChildNode(project.id(), setup.activeRoute.id(),
                        parentId,
                        GraphStep.renderText(scenario.scenarioId(), child.questionSeed(),
                                variant.paraphraseIndex(), Map.of()),
                        "eval purpose", List.of(), child.allowFreeAnswer());
                setup.stepNodes.put(ref, node.id());
                setup.activeRoute = routeRepository.findById(setup.activeRoute.id()).orElseThrow();
            } else if (step instanceof GraphStep.AttachResource resource) {
                Node node = graphCommandService.attachResource(project.id(),
                        setup.activeRoute.id(), setup.activeRoute.tipNodeId(), "TEXT",
                        Map.of("text", GraphStep.renderText(scenario.scenarioId(),
                                resource.textSeed(), variant.paraphraseIndex(), Map.of())));
                setup.stepNodes.put(ref, node.id());
                setup.activeRoute = routeRepository.findById(setup.activeRoute.id()).orElseThrow();
            } else if (step instanceof GraphStep.ForkFromNode fork) {
                UUID sourceId = requireStepNode(setup, fork.sourceStepRef());
                answerForSetup(project.id(), setup.activeRoute.id(), sourceId);
                Route forked = routeService.forkFromNode(project.id(),
                        setup.activeRoute.id(), sourceId,
                        "eval-" + fork.labelSeed() + "-" + variant.seed());
                setup.stepRoutes.put(ref, forked.id());
                setup.forkRoute = forked;
            } else if (step instanceof GraphStep.SetFocus focus) {
                setup.focusNodeId = requireStepNode(setup, focus.targetStepRef());
            } else if (step instanceof GraphStep.SetKnowledgeStatus status) {
                UUID targetId = requireStepNode(setup, status.targetStepRef());
                nodeService.setKnowledgeStatus(project.id(), targetId,
                        KnowledgeStatus.fromCode(status.status()));
                setup.stepNodes.put(ref, targetId);
            }
        }

        for (ResourceSpec resource : scenario.given().resources()) {
            graphCommandService.attachResource(project.id(), setup.activeRoute.id(),
                    setup.activeRoute.tipNodeId(), "TEXT",
                    Map.of("text", GraphStep.renderText(scenario.scenarioId(),
                            resource.textSeed(), variant.paraphraseIndex(), Map.of())));
            setup.activeRoute = routeRepository.findById(setup.activeRoute.id()).orElseThrow();
        }

        // 焦点不同于活动路由:先访问另一条路由,再回到目标路由,让回答周期
        // 在场景声明的位置执行。焦点永远不选中 Answer;这一步只是证明
        // 焦点放置无法改变 Answer 的归属。
        if (variant.focusDiffersFromActive() && setup.forkRoute != null) {
            routeService.setActiveRoute(project.id(), setup.forkRoute.id());
            routeService.setActiveRoute(project.id(), setup.activeRoute.id());
        }
        if (scenario.given().routeContext().kind() == RouteContextSpec.Kind.FORK_TIP
                && setup.forkRoute != null) {
            routeService.setActiveRoute(project.id(), setup.forkRoute.id());
            setup.activeRoute = routeRepository.findById(setup.forkRoute.id()).orElseThrow();
        } else if (scenario.given().routeContext().kind() == RouteContextSpec.Kind.ACTIVE_TIP
                && setup.forkRoute != null) {
            // forkFromNode 会把工作区活动路由移到分叉上。ACTIVE_TIP 声明
            // 表示回答周期要在分叉前的原路由上运行,所以这里恢复它
            // (若焦点处理已经回到原路由,此操作幂等)。
            routeService.setActiveRoute(project.id(), setup.activeRoute.id());
            setup.activeRoute = routeRepository.findById(setup.activeRoute.id()).orElseThrow();
        }
        return setup;
    }

    private static List<Map.Entry<String, GraphStep>> topologicalWithShuffleTieBreak(
            List<Map.Entry<String, GraphStep>> declared, long seed) {
        List<Map.Entry<String, GraphStep>> remaining = new ArrayList<>(declared);
        List<Map.Entry<String, GraphStep>> ordered = new ArrayList<>();
        java.util.Set<String> done = new java.util.HashSet<>();
        while (!remaining.isEmpty()) {
            List<Map.Entry<String, GraphStep>> ready = new ArrayList<>();
            for (Map.Entry<String, GraphStep> entry : remaining) {
                if (done.containsAll(stepDependencies(entry.getValue()))) {
                    ready.add(entry);
                }
            }
            if (ready.isEmpty()) {
                throw new IllegalArgumentException(
                        "Cyclic graph step dependencies: " + remaining);
            }
            ready.sort((left, right) -> stepSortKey(left.getValue(), seed)
                    - stepSortKey(right.getValue(), seed));
            Map.Entry<String, GraphStep> next = ready.get(0);
            ordered.add(next);
            done.add(next.getKey());
            remaining.remove(next);
        }
        return ordered;
    }

    private static List<String> stepDependencies(GraphStep step) {
        if (step instanceof GraphStep.CreateChildQuestion child) {
            return List.of(child.parentStepRef());
        }
        if (step instanceof GraphStep.ForkFromNode fork) {
            return List.of(fork.sourceStepRef());
        }
        if (step instanceof GraphStep.SetFocus focus) {
            return focus.targetStepRef() == null ? List.of() : List.of(focus.targetStepRef());
        }
        if (step instanceof GraphStep.SetKnowledgeStatus status) {
            return List.of(status.targetStepRef());
        }
        return List.of();
    }

    private static int stepSortKey(GraphStep step, long seed) {
        return Math.floorMod((GraphStep.canonical(List.of(step)) + seed).hashCode(), 1024);
    }

    private UUID requireStepNode(Setup setup, String ref) {
        UUID nodeId = setup.stepNodes.get(ref);
        if (nodeId == null) {
            throw new IllegalArgumentException("Unknown graph step ref: " + ref);
        }
        return nodeId;
    }

    /**
     * 搭建期答案,用于满足分叉前置条件(分叉点需要一个已定稿的 Answer)。
     * 走生产 AnswerService,保证共享状态/发散类不变量依然权威。
     */
    private void answerForSetup(UUID projectId, UUID routeId, UUID nodeId) {
        if (answerRepository.existsByRouteAndNode(routeId, nodeId)) {
            return;
        }
        Answer answer = answerService.finalizeAnswer(projectId, routeId, nodeId,
                (String) null, "eval setup answer", "user");
        // 搭建期答案模拟一次已完成的用户周期。保持夹具在"路由推进"不变量
        // 之下仍然合法:没有 STATE_UPDATE 检查点的 Answer 被刻意设计为
        // 可恢复状态,而不是后续图搭建的正常前置条件。
        answerPatchService.save(projectId, routeId, nodeId, answer.id(), List.of(), null);
    }

    // -- 驱动(drive)--------------------------------------------------------------

    /** 即使生产链路 fail-closed,也把排队的 run id 带出来。 */
    private record DriveResult(UUID runId) {
    }

    private static final class DriveFailure extends RuntimeException {
        private final UUID runId;

        private DriveFailure(UUID runId, RuntimeException cause) {
            super(cause);
            this.runId = runId;
        }

        private String detail() {
            Throwable cause = getCause();
            return cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }

        private UUID runId() {
            return runId;
        }
    }

    private DriveResult driveUserEvent(ScenarioDefinition scenario, VariantSpec variant,
                                       Project project, Setup setup) {
        UserEvent event = scenario.given().userEvent();
        if (event instanceof UserEvent.AnswerTip answer) {
            Route route = routeRepository.findById(setup.activeRoute.id()).orElseThrow();
            String freeText = GraphStep.renderText(scenario.scenarioId(),
                    answer.freeTextSeed(), variant.paraphraseIndex(), Map.of());
            UUID queued = runService.createQueuedRunWithInput(
                    project.id(), "ANSWER_TIP", route.tipNodeId(), null, freeText, null,
                    answer.persistenceIntent());
            try {
                AgentRun claimed = runService.claimAnswerCycleRun(queued)
                        .orElseThrow(() -> new IllegalStateException(
                                "Enqueued answer-cycle run is not claimable: " + queued
                                        + " (" + describeRun(queued) + ")"));
                runWorker.executeRun(claimed);
            } catch (RuntimeException ex) {
                throw new DriveFailure(queued, ex);
            }
            return new DriveResult(queued);
        }
        if (event instanceof UserEvent.DivergentAnswer divergent) {
            UUID targetId = requireStepNode(setup, divergent.targetStepRef());
            Route route = setup.forkRoute != null
                    ? routeRepository.findById(setup.forkRoute.id()).orElseThrow()
                    : routeRepository.findById(setup.activeRoute.id()).orElseThrow();
            routeService.setActiveRoute(project.id(), route.id());
            String freeText = GraphStep.renderText(scenario.scenarioId(),
                    divergent.freeTextSeed(), variant.paraphraseIndex(), Map.of());
            UUID queued = runService.createQueuedRunWithInput(
                    project.id(), "ANSWER_TIP", targetId, null, freeText, null);
            try {
                AgentRun claimed = runService.claimAnswerCycleRun(queued)
                        .orElseThrow(() -> new IllegalStateException(
                                "Enqueued answer-cycle run is not claimable: " + queued
                                        + " (" + describeRun(queued) + ")"));
                runWorker.executeRun(claimed);
            } catch (RuntimeException ex) {
                throw new DriveFailure(queued, ex);
            }
            return new DriveResult(queued);
        }
        throw new IllegalArgumentException("Unknown user event: " + event);
    }

    /** run 按 id 认领,因此失败状态会在错误信息里自报身份。 */
    private String describeRun(UUID runId) {
        return runService.getRun(runId)
                .map(run -> "status=" + run.status().code() + ", trigger=" + run.triggerType().code())
                .orElse("row missing");
    }

    // -- 观察(observe)-------------------------------------------------------------

    /**
     * P2 阶段二——live 观察。
     *
     * 读取与 {@link #observe} 相同的规范 Java 运行时事实,但生产调用记账
     * 从持久化的运行事件推导,而非脚本化 Brain:{@code STATE_UPDATE_STARTED} /
     * {@code DECISION_STARTED} 标记两次生产调用,{@code MODEL_INFERENCE_FAILED}
     * 标记 provider 重试(绝不算新的生产步骤),每次调用的延迟来自 broker 的
     * {@code MODEL_INFERENCE} 事件里的 {@code elapsedMillis}。token 保持 null
     * (运行时只把哈希 + 类别 + 计时写入运行事件,从不写 token 数),成本保持
     * {@code unknown}——评测框架两者都不臆测。能力调用仍来自探针适配器。
     */
    private AttemptContext observeLive(ScenarioDefinition scenario, VariantSpec variant,
                                       Project project, UUID runId,
                                       StateSummary preState, int preRelations,
                                       List<UUID> preAnswerIds,
                                       String failureDetail,
                                       long latencyMs) {
        AgentRun run = runId == null ? null : agentRunService.getRun(runId).orElse(null);
        List<AttemptContext.AgentRunEventView> events = new ArrayList<>();
        if (run != null) {
            for (var event : eventService.findByRunId(run.id())) {
                events.add(new AttemptContext.AgentRunEventView(
                        event.eventType(), Map.copyOf(event.payload())));
            }
        }
        LiveCallAccounting accounting = deriveLiveCallAccounting(events);
        StateSummary postState = summarize(project.id());
        Map<String, Integer> delta = new LinkedHashMap<>();
        delta.put("routes", postState.routes() - preState.routes());
        delta.put("nodes", postState.nodes() - preState.nodes());
        delta.put("answers", postState.answers() - preState.answers());
        delta.put("patches", postState.patches() - preState.patches());
        delta.put("proposals", postState.proposals() - preState.proposals());
        delta.put("relations", countRelations(project.id()) - preRelations);
        delta.put("capabilityInvocations",
                postState.capabilityInvocations() - preState.capabilityInvocations());

        List<AgentProposal> proposals = run == null ? List.of()
                : proposalService.findByRunId(run.id()).map(List::of).orElse(List.of());
        String action = primaryAction(events);
        String executionResult = executionResult(events, proposals, failureDetail);
        Map<String, Integer> capabilityInvocations = readProbeInvocations();
        List<UUID> postAnswerIds = answerIds(project.id());
        ContextSnapshot decisionSnapshot = decisionSnapshot(events);
        Map<String, Long> stageLatency = new LinkedHashMap<>();
        stageLatency.put("total", latencyMs);
        stageLatency.putAll(accounting.stageLatencyMs());

        return new AttemptContext(
                project.id(), runId, run, events, preState, postState, delta,
                action, executionResult, proposals, capabilityInvocations,
                preAnswerIds, postAnswerIds, decisionSnapshot,
                takeSemanticTrace(runId),
                accounting.stages(), accounting.providerRetries(),
                latencyMs, stageLatency, failureDetail);
    }

    /**
     * 从持久化的运行事件推导 live 调用记账(权威事实来源)。生产调用是
     * AnswerCycle 拥有的 brain 操作边界(先 STATE_UPDATE 后 DECISION);
     * provider 重试是 broker 侧的推理失败,绝不变成新的生产步骤。两个计数
     * 都来自运行时本就持久化的事件——评测框架不添加第二套流程模型。
     */
    private static LiveCallAccounting deriveLiveCallAccounting(
            List<AttemptContext.AgentRunEventView> events) {
        List<String> stages = new ArrayList<>();
        int retries = 0;
        Map<String, Long> stageLatencyMs = new LinkedHashMap<>();
        int inferenceIndex = 0;
        for (AttemptContext.AgentRunEventView event : events) {
            switch (event.eventType()) {
                case "STATE_UPDATE_STARTED" -> stages.add("STATE_UPDATE");
                case "DECISION_STARTED" -> stages.add("DECISION");
                case "MODEL_INFERENCE_FAILED" -> retries++;
                case "MODEL_INFERENCE" -> {
                    Object elapsed = event.payload().get("elapsedMillis");
                    if (elapsed instanceof Number number) {
                        Object callType = event.payload().get("callType");
                        String key = "inference."
                                + (callType == null ? indexSuffix(inferenceIndex) : callType);
                        stageLatencyMs.put(key, number.longValue());
                        inferenceIndex++;
                    }
                }
                default -> {
                }
            }
        }
        return new LiveCallAccounting(stages, retries, stageLatencyMs);
    }

    private static String indexSuffix(int index) {
        return "call-" + index;
    }

    private record LiveCallAccounting(List<String> stages, int providerRetries,
                                      Map<String, Long> stageLatencyMs) {
    }

    private AttemptContext observe(ScenarioDefinition scenario, VariantSpec variant,
                                   Project project, UUID runId,
                                   StateSummary preState, int preRelations,
                                   List<UUID> preAnswerIds,
                                   BrainScriptInstaller brain, String failureDetail,
                                   long latencyMs) {
        AgentRun run = runId == null ? null : agentRunService.getRun(runId).orElse(null);
        List<AttemptContext.AgentRunEventView> events = new ArrayList<>();
        if (run != null) {
            for (var event : eventService.findByRunId(run.id())) {
                events.add(new AttemptContext.AgentRunEventView(
                        event.eventType(), Map.copyOf(event.payload())));
            }
        }
        StateSummary postState = summarize(project.id());
        Map<String, Integer> delta = new LinkedHashMap<>();
        delta.put("routes", postState.routes() - preState.routes());
        delta.put("nodes", postState.nodes() - preState.nodes());
        delta.put("answers", postState.answers() - preState.answers());
        delta.put("patches", postState.patches() - preState.patches());
        delta.put("proposals", postState.proposals() - preState.proposals());
        delta.put("relations", countRelations(project.id()) - preRelations);
        delta.put("capabilityInvocations",
                postState.capabilityInvocations() - preState.capabilityInvocations());

        List<AgentProposal> proposals = run == null ? List.of()
                : proposalService.findByRunId(run.id()).map(List::of).orElse(List.of());
        String action = primaryAction(events);
        String executionResult = executionResult(events, proposals, failureDetail);
        Map<String, Integer> capabilityInvocations = readProbeInvocations();
        List<UUID> postAnswerIds = answerIds(project.id());
        ContextSnapshot decisionSnapshot = decisionSnapshot(events);

        return new AttemptContext(
                project.id(), runId, run, events, preState, postState, delta,
                action, executionResult, proposals, capabilityInvocations,
                preAnswerIds, postAnswerIds, decisionSnapshot,
                takeSemanticTrace(runId),
                brain.observedStages(), brain.providerRetries(),
                latencyMs, Map.of("total", latencyMs), failureDetail);
    }

    private static String primaryAction(List<AttemptContext.AgentRunEventView> events) {
        for (var event : events) {
            if ("PROPOSAL_CREATED".equals(event.eventType())
                    && event.payload().get("actionFamily") != null) {
                return String.valueOf(event.payload().get("actionFamily"));
            }
        }
        for (var event : events) {
            if ("RUN_COMPLETED".equals(event.eventType())
                    && event.payload().get("actionFamily") != null) {
                return String.valueOf(event.payload().get("actionFamily"));
            }
        }
        return null;
    }

    private static String executionResult(List<AttemptContext.AgentRunEventView> events,
                                          List<AgentProposal> proposals,
                                          String failureDetail) {
        if (failureDetail != null) {
            return "failed:" + failureDetail;
        }
        for (var event : events) {
            if ("RUN_FAILED".equals(event.eventType())) {
                return "failed:" + event.payload().getOrDefault("reason", "unknown");
            }
        }
        for (var event : events) {
            if ("AWAITING_APPROVAL".equals(event.eventType())) {
                return "awaiting_approval:" + event.payload().get("proposalId");
            }
        }
        for (AgentProposal proposal : proposals) {
            if (proposal.status() == ProposalStatus.EXPIRED) {
                return "policy_denied";
            }
        }
        for (var event : events) {
            if ("RUN_COMPLETED".equals(event.eventType())) {
                return "completed";
            }
        }
        return "unknown";
    }

    private ContextSnapshot decisionSnapshot(List<AttemptContext.AgentRunEventView> events) {
        for (var event : events) {
            if ("DECISION_STARTED".equals(event.eventType())
                    && event.payload().get("snapshotId") != null) {
                try {
                    UUID snapshotId = UUID.fromString(
                            String.valueOf(event.payload().get("snapshotId")));
                    return contextSnapshotRepository.findById(snapshotId).orElse(null);
                } catch (IllegalArgumentException ex) {
                    return null;
                }
            }
        }
        return null;
    }

    private SemanticTrace takeSemanticTrace(UUID runId) {
        return semanticTraceRecorder.take(runId);
    }

    private StateSummary summarize(UUID projectId) {
        int routes = routeRepository.findByProject(projectId).size();
        List<Node> nodes = nodeService.listProject(projectId);
        int answers = 0;
        for (Node node : nodes) {
            if (answerRepository.existsByNodeId(node.id())) {
                answers++;
            }
        }
        int patches = 0;
        for (Route route : routeRepository.findByProject(projectId)) {
            patches += answerPatchService.findByRoute(route.id()).size();
        }
        int proposals = proposalService.getByStatus(projectId, ProposalStatus.PROPOSED).size()
                + proposalService.getByStatus(projectId, ProposalStatus.ACCEPTED).size()
                + proposalService.getByStatus(projectId, ProposalStatus.EXPIRED).size();
        Map<String, Integer> probeCounts = readProbeInvocations();
        int invocations = probeCounts.values().stream().mapToInt(Integer::intValue).sum();
        return new StateSummary(routes, nodes.size(), answers, patches, proposals, invocations);
    }

    private int countRelations(UUID projectId) {
        return graphCommandService.listRelations(projectId).size();
    }

    /** 项目的规范 Answer 身份列表(每条路由 × 其节点)。 */
    private List<UUID> answerIds(UUID projectId) {
        List<Node> nodes = nodeService.listProject(projectId);
        List<UUID> nodeIds = nodes.stream().map(Node::id).toList();
        List<UUID> ids = new ArrayList<>();
        for (Route route : routeRepository.findByProject(projectId)) {
            for (Answer answer : answerService.findAnswersForRouteAndNodeIds(route.id(), nodeIds)) {
                if (!ids.contains(answer.id())) {
                    ids.add(answer.id());
                }
            }
        }
        return ids;
    }

    // -- 组装(assemble)-----------------------------------------------------------

    private ObservationEnvelope assemble(ScenarioDefinition scenario, VariantSpec variant,
                                         AttemptContext context) {
        return assemble(scenario, variant, context, EvaluationProfile.B_FAST, variant.seed());
    }

    /**
     * 两种档位共用的契约组装:Layer A 不变量、Layer B 期望、调用预算——然后
     * 用实际产生观察的档位给信封盖章。期望绝不会从场景的脚本化 Brain 输出
     * 复制;B-live 复用契约声明的同一套可接受/禁止动作、属性和增量。
     */
    private ObservationEnvelope assemble(ScenarioDefinition scenario, VariantSpec variant,
                                         AttemptContext context, EvaluationProfile profile,
                                         long observationSeed) {
        List<Violation> violations = new ArrayList<>();
        List<CheckResult> invariantResults = LayerA.check(scenario, context);
        for (CheckResult check : invariantResults) {
            if (!check.passed()) {
                violations.add(new Violation(FailureClass.RUNTIME_INVARIANT,
                        check.name() + ": " + check.detail()));
            }
        }
        List<CheckResult> propertyResults = LayerBFast.checkProperties(scenario, context);
        for (CheckResult check : propertyResults) {
            if (!check.passed()) {
                violations.add(new Violation(FailureClass.REQUIRED_PROPERTY_MISSING,
                        check.name() + ": " + check.detail()));
            }
        }
        violations.addAll(LayerBFast.checkActions(scenario, context));
        violations.addAll(LayerBFast.checkStateDeltas(scenario, context));

        CallBudgetTracker tracker = CallBudgetTracker.empty();
        for (String stage : context.observedStages()) {
            tracker.recordProductionCall(stage);
        }
        for (int i = 0; i < context.providerRetries(); i++) {
            tracker.recordProviderRetry();
        }
        int capabilityCalls = context.capabilityInvocations().values().stream()
                .mapToInt(Integer::intValue).sum();
        for (int i = 0; i < capabilityCalls; i++) {
            tracker.recordCapabilityCall();
        }
        violations.addAll(tracker.check(scenario.expect().callBudget()));

        SemanticTrace trace = context.semanticTrace();
        if (!trace.isEmpty()) {
            trace = trace.withStage("FINAL_RESULT", finalResult(
                    context, violations, tracker));
        }

        StateSummary pre = context.preState();
        StateSummary post = context.postState();
        return ObservationEnvelope.builder(
                        scenario.scenarioId(), variant.variantId(),
                        scenario.scenarioHash(), profile)
                .preState(new StateSummary(
                        pre.routes(), pre.nodes(), pre.answers(), pre.patches(),
                        pre.proposals(), pre.capabilityInvocations()))
                .postState(new StateSummary(
                        post.routes(), post.nodes(), post.answers(), post.patches(),
                        post.proposals(), post.capabilityInvocations()))
                .actualPrimaryAction(context.actualPrimaryAction())
                .executionResult(context.executionResult())
                .stateDelta(context.stateDelta())
                .invariantResults(invariantResults)
                .propertyResults(propertyResults)
                .violations(violations)
                .callBudget(tracker)
                .contextSnapshot(
                        "agent-input.v2",
                        context.decisionSnapshot() == null
                                ? null : context.decisionSnapshot().contextHash())
                .latencyMs(context.latencyMs())
                .stageLatencyMs(context.stageLatencyMs())
                .attemptId(context.runId())
                .semanticTrace(trace)
                .seed(observationSeed)
                .build();
    }

    private static Map<String, Object> finalResult(AttemptContext context,
                                                   List<Violation> violations,
                                                   CallBudgetTracker tracker) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("actual_action", context.actualPrimaryAction());
        result.put("state_delta_summary", context.stateDelta());
        result.put("evaluation_pass", violations.isEmpty());
        result.put("violations", violations.stream().map(violation -> Map.of(
                "failure_class", violation.failureClass().name(),
                "detail", violation.detail() == null ? "" : violation.detail())).toList());
        result.put("infrastructure_error", context.failureDetail());
        result.put("provider_retry_count", tracker.providerRetries());
        result.put("model_invocation_counts", Map.of(
                "production", tracker.productionModelCalls(),
                "capability", tracker.capabilityCalls(),
                "judge", tracker.judgeModelCalls()));
        return result;
    }

    // -- 测试范围的探针桥接 -----------------------------------------------------------
    //
    // 探针能力位于测试范围(固定的通用夹具)。运行器通过这条窄的反射桥读取
    // 它们的调用计数、安装按场景的成功标志,使 main 范围的代码永远不依赖
    // 测试类型。

    private void resetProbeCapabilities() {
        invokeProbe("reset");
    }

    /**
     * 为 live 尝试安装按场景的能力成功标志。探针适配器默认成功;声明了
     * {@code succeed=false} 能力的场景需要通过同一条反射桥写入标志
     * (main 范围的代码永远不依赖测试类型)。
     */
    private void installCapabilitySuccessFlags(ScenarioDefinition scenario) {
        for (CapabilitySpec capability : scenario.given().capabilities()) {
            if (!capability.succeed()) {
                setProbeSuccess(capability.capabilityId(), false);
            }
        }
    }

    private void setProbeSuccess(String capabilityId, boolean succeed) {
        try {
            Class<?> probe = Class.forName("com.specagent.eval.EvalProbeCapabilities");
            Object succeedMap = probe.getField("SUCCEED").get(null);
            if (succeedMap instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Boolean> flags = (Map<String, Boolean>) map;
                flags.put(capabilityId, succeed);
                return;
            }
            throw new IllegalStateException("Eval probe SUCCEED map unavailable");
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Eval probes unavailable", ex);
        }
    }

    private void invokeProbe(String method) {
        try {
            Class<?> probe = Class.forName("com.specagent.eval.EvalProbeCapabilities");
            probe.getMethod(method).invoke(null);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Eval probes unavailable", ex);
        }
    }

    private Map<String, Integer> readProbeInvocations() {        try {
            Class<?> probe = Class.forName("com.specagent.eval.EvalProbeCapabilities");
            Object counts = probe.getMethod("invocationCounts").invoke(null);
            if (counts instanceof Map<?, ?> map) {
                Map<String, Integer> result = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (entry.getValue() instanceof Number number) {
                        result.put(String.valueOf(entry.getKey()), number.intValue());
                    }
                }
                return result;
            }
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("Eval probes unavailable", ex);
        }
        return Map.of();
    }

    private static final class Setup {
        private Route activeRoute;
        private Route forkRoute;
        private UUID focusNodeId;
        private final Map<String, UUID> stepNodes = new LinkedHashMap<>();
        private final Map<String, UUID> stepRoutes = new LinkedHashMap<>();
    }
}
