package com.specagent.eval;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.policy.AgentProposal;
import com.specagent.agent.policy.AgentProposalService;
import com.specagent.agent.policy.ProposalStatus;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerRepository;
import com.specagent.context.ContextSnapshot;
import com.specagent.context.ContextSnapshotRepository;
import com.specagent.graph.GraphCommandService;
import com.specagent.node.KnowledgeStatus;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatchService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import com.specagent.route.RouteService;
import com.specagent.answer.AnswerService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * P2 evaluation scenario runner.
 *
 * <p>Real execution chain for one attempt:
 * <pre>
 * declarative Scenario
 *   → canonical Java runtime setup (project, graph, capabilities, resources)
 *   → production answer cycle (RunService + RunWorker + AnswerCycleService)
 *   → STATE_UPDATE → Java validate/apply → post-update ContextSnapshot
 *   → DECISION → Java policy/execution
 *   → Observation (canonical state only) → Layer A / B-fast checks
 *   → JSONL-ready ObservationEnvelope
 * </pre>
 *
 * <p>The runner only observes, orchestrates, and asserts. Graph, routes,
 * nodes, Answer persistence, patch lifecycle, snapshots, authorization,
 * confirmation, capability policy, and canonical mutation stay owned by
 * the Java runtime. The scripted B-fast brain replaces only the Brain
 * structured output at the boundary; production call counting reads the
 * scripted engine's recorded stages.
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
                          ObjectProvider<BrainScriptInstaller> brainScripts) {
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
    }

    public UUID lastProjectId() {
        return lastProjectId;
    }

    /** Runs one scenario variant end to end and returns its observation. */
    public ObservationEnvelope run(ScenarioDefinition scenario, VariantSpec variant) {
        return run(scenario, variant, EvaluationProfile.B_FAST);
    }

    /**
     * P2 Phase 2 — Live behavioral baseline entry.
     *
     * <p>Runs the identical Scenario Contract (same canonical Java runtime
     * setup, same Layer A invariants, same Layer B expectations, same call
     * budget) but lets the production Brain wiring answer STATE_UPDATE +
     * DECISION instead of installing a scripted Brain output. The scenario's
     * {@code given.brainScript} is ignored — never copied as an expectation —
     * so a live Brain that genuinely converges on an acceptable action still
     * passes, and one that diverges fails through the unchanged contract.
     *
     * <p>Requires that no {@code BrainScriptInstaller} bean is active: when a
     * scripted brain is installed the runner refuses to run live, so live
     * observations can never silently come from scripted outputs.
     *
     * @param repetitionSeed recorded seed for this repetition (N=3 runs of
     *     the same variant differ only by this seed, never by semantics)
     */
    public ObservationEnvelope runLive(ScenarioDefinition scenario, VariantSpec variant,
                                       long repetitionSeed) {
        if (brainScripts.getIfAvailable() != null) {
            throw new IllegalStateException(
                    "runLive requires the production Brain wiring: a BrainScriptInstaller "
                            + "is active, refusing to mix scripted outputs into live observations");
        }
        return run(scenario, variant, EvaluationProfile.LIVE_PROVIDER, repetitionSeed);
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

        // Post-observe even on failure: fail-closed means canonical state
        // must show no unintended mutation.
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

    // -- setup -----------------------------------------------------------------

    private String renderTitle(ScenarioDefinition scenario, VariantSpec variant) {
        String label = "eval-" + scenario.scenarioId() + "-" + variant.variantId()
                + "-" + scenario.given().projectTitleSeed() + "-" + variant.seed();
        if (variant.routeVariation() != null && !variant.routeVariation().isBlank()) {
            label += "-" + variant.routeVariation();
        }
        return label;
    }

    /** Builds the declarative initial graph; keys map step refs to nodes/routes. */
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
            // Reorder only dependency-free context: steps are executed in
            // topological order (dependencies first) with the shuffle key as
            // the tie-break, so references never dangle while irrelevant
            // ordering still varies across variants.
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

        // Focus differs from active: visit another route, then return to the
        // target route so the answer cycle runs where the scenario declares.
        // Focus never selects an Answer; this only proves focus placement
        // cannot change answer ownership.
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
            // forkFromNode moves the workspace active route onto the fork.
            // An ACTIVE_TIP declaration means the answer cycle runs on the
            // pre-fork route, so restore it (idempotent when focus handling
            // already returned there).
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
     * Setup-time answer used to satisfy fork preconditions (branch points
     * need a finalized answer). Goes through the production AnswerService so
     * shared-state/divergence invariants stay authoritative.
     */
    private void answerForSetup(UUID projectId, UUID routeId, UUID nodeId) {
        if (answerRepository.existsByRouteAndNode(routeId, nodeId)) {
            return;
        }
        answerService.finalizeAnswer(projectId, routeId, nodeId,
                null, "eval setup answer", "user");
    }

    // -- drive -------------------------------------------------------------------

    /** Carries the queued run id even when the production path fails closed. */
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
                    project.id(), "ANSWER_TIP", route.tipNodeId(), null, freeText, null);
            try {
                AgentRun claimed = runService.claimNextAnswerCycle()
                        .orElseThrow(() -> new IllegalStateException("No queued answer-cycle run"));
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
                AgentRun claimed = runService.claimNextAnswerCycle()
                        .orElseThrow(() -> new IllegalStateException("No queued answer-cycle run"));
                runWorker.executeRun(claimed);
            } catch (RuntimeException ex) {
                throw new DriveFailure(queued, ex);
            }
            return new DriveResult(queued);
        }
        throw new IllegalArgumentException("Unknown user event: " + event);
    }

    // -- observe -------------------------------------------------------------------

    /**
     * P2 Phase 2 — live observation.
     *
     * <p>Reads the same canonical Java runtime facts as {@link #observe}, but
     * derives production-call accounting from the persisted run events instead
     * of a scripted brain: {@code STATE_UPDATE_STARTED} / {@code DECISION_STARTED}
     * mark the two production calls, {@code MODEL_INFERENCE_FAILED} marks
     * provider retries (never new production steps), and per-call latency
     * comes from the broker's {@code MODEL_INFERENCE} {@code elapsedMillis}.
     * Tokens stay null (the runtime persists only hashes + category + timing,
     * never token counts into run events) and cost stays {@code unknown} —
     * the harness never guesses either. Capability invocations still come
     * from the probe adapters.
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
                accounting.stages(), accounting.providerRetries(),
                latencyMs, stageLatency, failureDetail);
    }

    /**
     * Derives live call accounting from persisted run events (canonical truth).
     * Production calls are the brain-operation boundaries the AnswerCycle owns
     * (STATE_UPDATE then DECISION); provider retries are broker-side inference
     * failures that never become new production steps. Both counts come from
     * events the runtime already persists — the harness adds no second model
     * of the flow.
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

    /** Canonical answer identities of the project (every route × its nodes). */
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

    // -- assemble -------------------------------------------------------------------

    private ObservationEnvelope assemble(ScenarioDefinition scenario, VariantSpec variant,
                                         AttemptContext context) {
        return assemble(scenario, variant, context, EvaluationProfile.B_FAST, variant.seed());
    }

    /**
     * Shared contract assembly for both profiles: Layer A invariants, Layer B
     * expectations, call budget — then the envelope stamped with the profile
     * that actually produced the observation. Expectations are never copied
     * from the scenario's scripted brain output; B-live reuses the same
     * acceptable/forbidden actions, properties and deltas the contract
     * declares.
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
                .seed(observationSeed)
                .build();
    }

    // -- test-scope probe bridge ------------------------------------------------------
    //
    // Probe capabilities live in test scope (fixed generic fixtures). The
    // runner reads their invocation counts and installs per-scenario
    // success flags through this narrow reflective bridge so main-scope
    // code never depends on test types.

    private void resetProbeCapabilities() {
        invokeProbe("reset");
    }

    /**
     * Installs the per-scenario capability success flags for live attempts.
     * Probe adapters default to success; scenarios that declare a capability
     * with {@code succeed=false} need the flag written through the same
     * reflective bridge (main scope never depends on test types).
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
