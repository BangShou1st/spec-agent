package com.specagent.agent;

import com.specagent.agent.contracts.AnswerInterpretationResult;
import com.specagent.agent.contracts.AnswerPatchDraft;
import com.specagent.agent.contracts.NodeDraft;
import com.specagent.agent.contracts.ReflectionResult;
import com.specagent.agent.contracts.SpecDraft;
import com.specagent.agent.gates.ContextGuard;
import com.specagent.agent.gates.NodeReflectionGate;
import com.specagent.agent.gates.PatchReflectionGate;
import com.specagent.agent.gates.SpecGroundingGate;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerService;
import com.specagent.common.Json;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.patch.AnswerPatch;
import com.specagent.patch.AnswerPatchService;
import com.specagent.patch.Claim;
import com.specagent.patch.ClaimStatus;
import com.specagent.project.Project;
import com.specagent.project.ProjectRepository;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import com.specagent.spec.SourceKind;
import com.specagent.spec.SourceReference;
import com.specagent.spec.SpecSection;
import com.specagent.spec.SpecSnapshot;
import com.specagent.spec.SpecSnapshotService;
import com.specagent.spec.UnresolvedItem;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runtime-controlled fake agent orchestrator.
 *
 * <p>Runs fake agent cycles against the fake model adapter: create an agent
 * run, freeze a context snapshot, ask the fake model for a proposal, validate
 * the proposal through the reflection gates, persist the accepted outcome
 * through runtime services, and close the run. The orchestrator only proposes
 * through the model adapter; all persistence happens through runtime services.
 *
 * <p>{@link #draftNextQuestion} runs one DRAFT_NODE cycle.
 * {@link #answerActiveNodeAndDraftNext} runs the answer loop: persist the
 * immutable answer, interpret it, draft and ground an answer patch, then draft
 * the next node. {@link #generateSpec} runs the spec loop: draft a spec,
 * validate grounding, and persist a derived spec snapshot. Neither loop wraps
 * the cycle in a single transaction: each runtime step persists on its own, and
 * unexpected failures are recorded through {@link AgentRunFailureService} in a
 * separate transaction so the FAILED run stays queryable after the exception is
 * rethrown.
 */
@Service
public class FakeAgentOrchestrator {

    private final AgentRunService agentRunService;
    private final AgentRunFailureService agentRunFailureService;
    private final ProjectRepository projectRepository;
    private final RouteRepository routeRepository;
    private final ContextBuilder contextBuilder;
    private final ContextGuard contextGuard;
    private final FakeModelAdapter fakeModelAdapter;
    private final NodeReflectionGate nodeReflectionGate;
    private final PatchReflectionGate patchReflectionGate;
    private final SpecGroundingGate specGroundingGate;
    private final NodeService nodeService;
    private final AnswerService answerService;
    private final AnswerPatchService answerPatchService;
    private final SpecSnapshotService specSnapshotService;
    private final Json json;

    public FakeAgentOrchestrator(AgentRunService agentRunService,
                                 AgentRunFailureService agentRunFailureService,
                                 ProjectRepository projectRepository,
                                 RouteRepository routeRepository,
                                 ContextBuilder contextBuilder,
                                 ContextGuard contextGuard,
                                 FakeModelAdapter fakeModelAdapter,
                                 NodeReflectionGate nodeReflectionGate,
                                 PatchReflectionGate patchReflectionGate,
                                 SpecGroundingGate specGroundingGate,
                                 NodeService nodeService,
                                 AnswerService answerService,
                                 AnswerPatchService answerPatchService,
                                 SpecSnapshotService specSnapshotService,
                                 Json json) {
        this.agentRunService = agentRunService;
        this.agentRunFailureService = agentRunFailureService;
        this.projectRepository = projectRepository;
        this.routeRepository = routeRepository;
        this.contextBuilder = contextBuilder;
        this.contextGuard = contextGuard;
        this.fakeModelAdapter = fakeModelAdapter;
        this.nodeReflectionGate = nodeReflectionGate;
        this.patchReflectionGate = patchReflectionGate;
        this.specGroundingGate = specGroundingGate;
        this.nodeService = nodeService;
        this.answerService = answerService;
        this.answerPatchService = answerPatchService;
        this.specSnapshotService = specSnapshotService;
        this.json = json;
    }

    public FakeAgentRunResult draftNextQuestion(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.activeRouteId() == null) {
            throw new IllegalStateException("Project has no active route: " + projectId);
        }

        Route route = routeRepository.findById(project.activeRouteId())
                .orElseThrow(() -> new IllegalArgumentException("Active route not found: " + project.activeRouteId()));

        AgentRun run = agentRunService.create(
                projectId,
                route.id(),
                AgentRunTriggerType.INITIAL_REQUIREMENT,
                route.tipNodeId(),
                null);

        try {
            ContextSnapshot contextSnapshot = contextBuilder.buildFromActiveRoute(
                    projectId,
                    run.id(),
                    ContextOperationType.NORMAL);
            agentRunService.attachContext(run.id(), contextSnapshot.id(), "context_built");

            ReflectionResult contextReflection = contextGuard.validate(contextSnapshot);
            if (!contextReflection.accepted()) {
                agentRunService.fail(run.id(), json.write(contextReflection));
                throw new ModelContractException("Context guard rejected fake agent run");
            }

            ModelRequest request = new ModelRequest(
                    projectId,
                    route.id(),
                    run.id(),
                    contextSnapshot.id(),
                    AgentTaskType.DRAFT_NODE,
                    "{}",
                    Map.of("orchestrator", "fake"));

            ModelResponse response = fakeModelAdapter.run(request);
            agentRunService.markModelCalled(run.id(), json.write(response.trace()));

            if (response.action() != AgentAction.ASK_NEXT_QUESTION) {
                agentRunService.fail(run.id(), json.write(response));
                throw new ModelContractException("Expected ASK_NEXT_QUESTION from fake DRAFT_NODE");
            }

            NodeDraft draft = json.read(response.outputJson(), NodeDraft.class);
            ReflectionResult nodeReflection = nodeReflectionGate.validate(draft);
            agentRunService.markReflected(run.id(), json.write(nodeReflection));

            if (!nodeReflection.accepted()) {
                agentRunService.fail(run.id(), json.write(nodeReflection));
                throw new ModelContractException("Node reflection rejected fake node draft");
            }

            Node producedNode;
            if (route.tipNodeId() == null) {
                producedNode = nodeService.createRootNode(
                        projectId,
                        route.id(),
                        draft.question(),
                        draft.purpose(),
                        draft.options(),
                        draft.allowFreeAnswer());
            } else {
                producedNode = nodeService.createChildNode(
                        projectId,
                        route.id(),
                        route.tipNodeId(),
                        draft.question(),
                        draft.purpose(),
                        draft.options(),
                        draft.allowFreeAnswer());
            }

            agentRunService.markPersistedNode(run.id(), producedNode.id(), "produced_node");
            agentRunService.complete(run.id(), AgentRunStatus.COMPLETED, "completed");

            AgentRun completedRun = agentRunService.getRun(run.id()).orElseThrow();
            return new FakeAgentRunResult(completedRun, contextSnapshot, response, producedNode);
        } catch (RuntimeException ex) {
            AgentRun latest = agentRunService.getRun(run.id()).orElse(null);
            if (latest != null && latest.status() != AgentRunStatus.FAILED
                    && latest.status() != AgentRunStatus.COMPLETED) {
                // Record the failure in its own transaction so the FAILED run
                // survives the rethrow instead of being rolled back.
                agentRunFailureService.fail(run.id(), "{\"error\":\"" + ex.getClass().getSimpleName() + "\"}");
            }
            throw ex;
        }
    }

    /**
     * Runs the fake answer loop against the active route's tip node: persist the
     * immutable answer, interpret it through the fake model, ground the answer
     * patch with the real answered node and answer ids, validate it through
     * {@link PatchReflectionGate}, persist the patch, then draft and persist the
     * next child node.
     *
     * <p>If any reflection gate rejects the proposal, the rejected artifact is
     * never persisted and the run is marked FAILED.
     */
    public FakeAnswerRunResult answerActiveNodeAndDraftNext(UUID projectId, String freeText) {
        Project project = loadProject(projectId);
        Route route = loadActiveRoute(project);
        if (route.tipNodeId() == null) {
            throw new IllegalStateException("Active route has no tip node: " + route.id());
        }
        UUID answeredNodeId = route.tipNodeId();

        AgentRun run = agentRunService.create(
                projectId,
                route.id(),
                AgentRunTriggerType.ANSWER_NODE,
                answeredNodeId,
                null);

        try {
            ContextSnapshot contextSnapshot = buildAndValidateContext(run, projectId);

            Answer answer = answerService.finalizeAnswer(
                    projectId, route.id(), answeredNodeId, null, freeText, "fake_user");
            agentRunService.markPersistedAnswer(run.id(), answer.id(), "produced_answer");

            ModelResponse interpretResponse = callFakeModel(run, contextSnapshot,
                    AgentTaskType.INTERPRET_ANSWER,
                    json.write(Map.of("freeText", freeText, "nodeId", answeredNodeId, "answerId", answer.id())),
                    AgentAction.INTERPRET_ANSWER,
                    "Expected INTERPRET_ANSWER from fake INTERPRET_ANSWER");
            AnswerInterpretationResult interpretation =
                    json.read(interpretResponse.outputJson(), AnswerInterpretationResult.class);

            ModelResponse patchResponse = callFakeModel(run, contextSnapshot,
                    AgentTaskType.DRAFT_ANSWER_PATCH,
                    json.write(interpretation),
                    AgentAction.INTERPRET_ANSWER,
                    "Expected INTERPRET_ANSWER from fake DRAFT_ANSWER_PATCH");
            AnswerPatchDraft patchDraft = json.read(patchResponse.outputJson(), AnswerPatchDraft.class);

            // The fake model never fabricates real source ids. The runtime
            // grounds confirmed claims with the real answered node and answer
            // before the patch may enter requirement state.
            AnswerPatchDraft groundedDraft = withRealSources(patchDraft, answeredNodeId, answer.id());
            ReflectionResult patchReflection = patchReflectionGate.validate(groundedDraft);
            agentRunService.markReflected(run.id(), json.write(patchReflection));
            if (!patchReflection.accepted()) {
                agentRunService.fail(run.id(), json.write(patchReflection));
                throw new ModelContractException("Patch reflection rejected fake answer patch draft");
            }

            AnswerPatch patch = answerPatchService.save(
                    projectId, route.id(), answeredNodeId, answer.id(),
                    groundedDraft.claims(), run.id());
            agentRunService.markPersistedAnswerPatch(run.id(), patch.id(), "produced_patch");

            ModelResponse nodeResponse = callFakeModel(run, contextSnapshot,
                    AgentTaskType.DRAFT_NODE,
                    json.write(Map.of("answerId", answer.id(), "patchId", patch.id())),
                    AgentAction.ASK_NEXT_QUESTION,
                    "Expected ASK_NEXT_QUESTION from fake DRAFT_NODE");
            NodeDraft draft = json.read(nodeResponse.outputJson(), NodeDraft.class);

            ReflectionResult nodeReflection = nodeReflectionGate.validate(draft);
            agentRunService.markReflected(run.id(), json.write(nodeReflection));
            if (!nodeReflection.accepted()) {
                agentRunService.fail(run.id(), json.write(nodeReflection));
                throw new ModelContractException("Node reflection rejected fake node draft");
            }

            Node producedNode = nodeService.createChildNode(
                    projectId, route.id(), answeredNodeId,
                    draft.question(), draft.purpose(), draft.options(), draft.allowFreeAnswer());
            agentRunService.markPersistedNode(run.id(), producedNode.id(), "produced_node");
            agentRunService.complete(run.id(), AgentRunStatus.COMPLETED, "completed");

            AgentRun completedRun = agentRunService.getRun(run.id()).orElseThrow();
            return new FakeAnswerRunResult(completedRun, contextSnapshot,
                    interpretResponse, patchResponse, nodeResponse, answer, patch, producedNode);
        } catch (RuntimeException ex) {
            AgentRun latest = agentRunService.getRun(run.id()).orElse(null);
            if (latest != null && latest.status() != AgentRunStatus.FAILED
                    && latest.status() != AgentRunStatus.COMPLETED) {
                agentRunFailureService.fail(run.id(), "{\"error\":\"" + ex.getClass().getSimpleName() + "\"}");
            }
            throw ex;
        }
    }

    /**
     * Runs the fake spec loop against the active route's tip: draft a spec
     * through the fake model, validate grounding through
     * {@link SpecGroundingGate}, convert the draft to the existing
     * {@link SpecSection} / {@link SourceReference} / {@link UnresolvedItem}
     * model, and persist a derived {@link SpecSnapshot}.
     *
     * <p>If the gate rejects the draft, no spec snapshot is persisted and the
     * run is marked FAILED.
     */
    public FakeSpecRunResult generateSpec(UUID projectId) {
        Project project = loadProject(projectId);
        Route route = loadActiveRoute(project);
        if (route.tipNodeId() == null) {
            throw new IllegalStateException("Active route has no tip node: " + route.id());
        }

        AgentRun run = agentRunService.create(
                projectId,
                route.id(),
                AgentRunTriggerType.GENERATE_SPEC,
                route.tipNodeId(),
                null);

        try {
            ContextSnapshot contextSnapshot = buildAndValidateContext(run, projectId);

            ModelResponse response = callFakeModel(run, contextSnapshot,
                    AgentTaskType.DRAFT_SPEC,
                    "{}",
                    AgentAction.GENERATE_SPEC,
                    "Expected GENERATE_SPEC from fake DRAFT_SPEC");
            SpecDraft specDraft = json.read(response.outputJson(), SpecDraft.class);

            ReflectionResult grounding = specGroundingGate.validate(specDraft);
            agentRunService.markReflected(run.id(), json.write(grounding));
            if (!grounding.accepted()) {
                agentRunService.fail(run.id(), json.write(grounding));
                throw new ModelContractException("Spec grounding rejected fake spec draft");
            }

            List<SpecSection> sections = specDraft.sections().entrySet().stream()
                    .map(entry -> SpecSection.of(entry.getKey(), entry.getValue()))
                    .toList();
            List<UnresolvedItem> unresolvedItems = specDraft.unresolvedItems().stream()
                    .map(text -> UnresolvedItem.of(text, "unresolved"))
                    .toList();
            List<SourceReference> sourceRefs = specDraft.sourceRefsBySection().values().stream()
                    .flatMap(List::stream)
                    .map(this::toSourceReference)
                    .distinct()
                    .toList();

            SpecSnapshot snapshot = specSnapshotService.createSnapshot(
                    projectId, route.id(), route.tipNodeId(), contextSnapshot.id(),
                    "markdown", sections, unresolvedItems, sourceRefs, run.id());
            agentRunService.markPersistedSpecSnapshot(run.id(), snapshot.id(), "produced_spec_snapshot");
            agentRunService.complete(run.id(), AgentRunStatus.COMPLETED, "completed");

            AgentRun completedRun = agentRunService.getRun(run.id()).orElseThrow();
            return new FakeSpecRunResult(completedRun, contextSnapshot, response, snapshot);
        } catch (RuntimeException ex) {
            AgentRun latest = agentRunService.getRun(run.id()).orElse(null);
            if (latest != null && latest.status() != AgentRunStatus.FAILED
                    && latest.status() != AgentRunStatus.COMPLETED) {
                agentRunFailureService.fail(run.id(), "{\"error\":\"" + ex.getClass().getSimpleName() + "\"}");
            }
            throw ex;
        }
    }

    private Project loadProject(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
    }

    private Route loadActiveRoute(Project project) {
        if (project.activeRouteId() == null) {
            throw new IllegalStateException("Project has no active route: " + project.id());
        }
        return routeRepository.findById(project.activeRouteId())
                .orElseThrow(() -> new IllegalArgumentException("Active route not found: " + project.activeRouteId()));
    }

    private ContextSnapshot buildAndValidateContext(AgentRun run, UUID projectId) {
        ContextSnapshot contextSnapshot = contextBuilder.buildFromActiveRoute(
                projectId, run.id(), ContextOperationType.NORMAL);
        agentRunService.attachContext(run.id(), contextSnapshot.id(), "context_built");

        ReflectionResult contextReflection = contextGuard.validate(contextSnapshot);
        if (!contextReflection.accepted()) {
            agentRunService.fail(run.id(), json.write(contextReflection));
            throw new ModelContractException("Context guard rejected fake agent run");
        }
        return contextSnapshot;
    }

    private ModelResponse callFakeModel(AgentRun run,
                                        ContextSnapshot contextSnapshot,
                                        AgentTaskType taskType,
                                        String inputJson,
                                        AgentAction expectedAction,
                                        String actionErrorMessage) {
        ModelRequest request = new ModelRequest(
                run.projectId(),
                run.routeId(),
                run.id(),
                contextSnapshot.id(),
                taskType,
                inputJson,
                Map.of("orchestrator", "fake"));

        ModelResponse response = fakeModelAdapter.run(request);
        agentRunService.markModelCalled(run.id(), json.write(response.trace()));

        if (response.action() != expectedAction) {
            agentRunService.fail(run.id(), json.write(response));
            throw new ModelContractException(actionErrorMessage);
        }
        return response;
    }

    /**
     * Grounds confirmed claims with the real answered node and answer ids so the
     * patch may pass {@link PatchReflectionGate}. Non-confirmed claims keep their
     * model-provided provenance (which may be absent).
     */
    private AnswerPatchDraft withRealSources(AnswerPatchDraft draft, UUID sourceNodeId, UUID sourceAnswerId) {
        List<Claim> grounded = new ArrayList<>();
        for (Claim claim : draft.claims()) {
            if (claim.status() == ClaimStatus.CONFIRMED) {
                grounded.add(new Claim(claim.id(), claim.kind(), claim.text(), claim.status(),
                        claim.confidence(), sourceNodeId, sourceAnswerId));
            } else {
                grounded.add(claim);
            }
        }
        return new AnswerPatchDraft(grounded);
    }

    /**
     * Parses a spec source reference of the form {@code kind:uuid} as produced
     * by the fake model. The model may only reference runtime records it knows
     * from the request (context snapshot, route), never fabricated ids.
     */
    private SourceReference toSourceReference(String ref) {
        int separator = ref.indexOf(':');
        if (separator <= 0 || separator == ref.length() - 1) {
            throw new IllegalArgumentException("Spec source reference must be kind:uuid: " + ref);
        }
        SourceKind kind = SourceKind.fromCode(ref.substring(0, separator));
        UUID refId = UUID.fromString(ref.substring(separator + 1));
        return SourceReference.of(kind, refId);
    }
}