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
import com.specagent.agent.gates.SpecSourceReferenceGuard;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerService;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
import com.specagent.model.contract.StructuredModelOutputParser;
import com.specagent.model.gateway.ModelGateway;
import com.specagent.model.gateway.ModelGatewayException;
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
import com.specagent.route.RouteService;
import com.specagent.route.RegenerateResult;
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
 * Runtime-controlled production agent orchestrator.
 *
 * <p>Runs agent cycles against the model gateway: create an agent
 * run, freeze a context snapshot, ask the model for a proposal, validate
 * the proposal through the reflection gates, persist the accepted outcome
 * through runtime services, and close the run. The orchestrator only proposes
 * through the {@link ModelGateway} abstraction; all persistence happens
 * through runtime services.
 *
 * <p>{@link #draftNextQuestion} runs one DRAFT_NODE cycle.
 * {@link #answerActiveNodeAndDraftNext} runs the answer loop: persist the
 * immutable answer, interpret it, draft and ground an answer patch, then draft
 * the next node. {@link #repairAnswerProcessingAndDraftNext} resumes an
 * existing immutable answer whose patch step failed, without finalizing a
 * second answer. {@link #generateSpec} runs the spec loop: draft a spec,
 * validate grounding and source references, and persist a derived spec
 * snapshot.
 *
 * <p>Every run keeps a cumulative trace of its steps so the final trace shows
 * the major lifecycle steps instead of only the last one. No loop wraps the
 * cycle in a single transaction: each runtime step persists on its own, and
 * unexpected failures are recorded through {@link AgentRunFailureService} in a
 * separate transaction so the FAILED run stays queryable after the exception
 * is rethrown.
 */
@Service
public class AgentOrchestrator {

    private final AgentRunService agentRunService;
    private final AgentRunFailureService agentRunFailureService;
    private final ProjectRepository projectRepository;
    private final RouteRepository routeRepository;
    private final RouteService routeService;
    private final ContextBuilder contextBuilder;
    private final ContextGuard contextGuard;
    private final ModelGateway modelGateway;
    private final NodeReflectionGate nodeReflectionGate;
    private final PatchReflectionGate patchReflectionGate;
    private final SpecGroundingGate specGroundingGate;
    private final SpecSourceReferenceGuard specSourceReferenceGuard;
    private final NodeService nodeService;
    private final AnswerService answerService;
    private final AnswerPatchService answerPatchService;
    private final SpecSnapshotService specSnapshotService;
    private final ModelContextProjectionBuilder projectionBuilder;
    private final StructuredModelOutputParser structuredModelOutputParser;
    private final StructuredOutputMapper structuredOutputMapper;

    public AgentOrchestrator(AgentRunService agentRunService,
                                 AgentRunFailureService agentRunFailureService,
                                 ProjectRepository projectRepository,
                                 RouteRepository routeRepository,
                                 RouteService routeService,
                                 ContextBuilder contextBuilder,
                                 ContextGuard contextGuard,
                                 ModelGateway modelGateway,
                                 NodeReflectionGate nodeReflectionGate,
                                 PatchReflectionGate patchReflectionGate,
                                 SpecGroundingGate specGroundingGate,
                                 SpecSourceReferenceGuard specSourceReferenceGuard,
                                 NodeService nodeService,
                                 AnswerService answerService,
                                 AnswerPatchService answerPatchService,
                                 SpecSnapshotService specSnapshotService,
                                 ModelContextProjectionBuilder projectionBuilder,
                                 StructuredModelOutputParser structuredModelOutputParser,
                                 StructuredOutputMapper structuredOutputMapper) {
        this.agentRunService = agentRunService;
        this.agentRunFailureService = agentRunFailureService;
        this.projectRepository = projectRepository;
        this.routeRepository = routeRepository;
        this.routeService = routeService;
        this.contextBuilder = contextBuilder;
        this.contextGuard = contextGuard;
        this.modelGateway = modelGateway;
        this.nodeReflectionGate = nodeReflectionGate;
        this.patchReflectionGate = patchReflectionGate;
        this.specGroundingGate = specGroundingGate;
        this.specSourceReferenceGuard = specSourceReferenceGuard;
        this.nodeService = nodeService;
        this.answerService = answerService;
        this.answerPatchService = answerPatchService;
        this.specSnapshotService = specSnapshotService;
        this.projectionBuilder = projectionBuilder;
        this.structuredModelOutputParser = structuredModelOutputParser;
        this.structuredOutputMapper = structuredOutputMapper;
    }

    public AgentRunResult draftNextQuestion(UUID projectId) {
        Project project = loadProject(projectId);
        Route route = loadActiveRoute(project);

        AgentRun run = agentRunService.create(
                projectId,
                route.id(),
                AgentRunTriggerType.INITIAL_REQUIREMENT,
                route.tipNodeId(),
                null);
        String trace = "created";

        try {
            trace = appendTrace(trace, "context_built");
            ContextSnapshot contextSnapshot = buildAndValidateContext(run, projectId, trace);

            trace = appendTrace(trace, "model_called:" + AgentTaskType.DRAFT_NODE.name());
            ModelResponse response = callModel(run, contextSnapshot, trace,
                    AgentTaskType.DRAFT_NODE,
                    projectionBuilder.buildInputJson(contextSnapshot,
                            projectionBuilder.initialNodeTaskInput()),
                    AgentAction.ASK_NEXT_QUESTION,
                    "Expected ASK_NEXT_QUESTION from DRAFT_NODE");

            NodeDraft draft = structuredOutputMapper.toNodeDraft(
                    structuredModelOutputParser.parse(AgentTaskType.DRAFT_NODE, response.outputJson()));
            ReflectionResult nodeReflection = nodeReflectionGate.validate(draft);
            trace = appendTrace(trace, "reflected:NODE");
            agentRunService.markReflected(run.id(), trace);

            if (!nodeReflection.accepted()) {
                agentRunService.fail(run.id(), appendTrace(trace, "failed:node_reflection_rejected"));
                throw new ModelContractException("Node reflection rejected node draft");
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

            trace = appendTrace(trace, "persisted_node");
            agentRunService.markPersistedNode(run.id(), producedNode.id(), trace);
            trace = appendTrace(trace, "completed");
            agentRunService.complete(run.id(), AgentRunStatus.COMPLETED, trace);

            AgentRun completedRun = agentRunService.getRun(run.id()).orElseThrow();
            return new AgentRunResult(completedRun, contextSnapshot, response, producedNode);
        } catch (RuntimeException ex) {
            failIfNotTerminal(run.id(), trace, ex);
            throw ex;
        }
    }

    /**
     * Runs the answer loop against the active route's tip node with free
     * text only (no selected option).
     *
     * <p>If any reflection gate rejects the proposal, the rejected artifact is
     * never persisted and the run is marked FAILED. The immutable answer stays
     * persisted; use {@link #repairAnswerProcessingAndDraftNext} to resume.
     */
    public AnswerRunResult answerActiveNodeAndDraftNext(UUID projectId, String freeText) {
        return answerActiveNodeAndDraftNext(projectId, null, freeText);
    }

    /**
     * Runs the answer loop against the active route's tip node with an
     * optional selected option and/or free text.
     *
     * <p>The selected option is runtime-validated: it must belong to the exact
     * active tip node. A selected option from another node, from a sibling
     * route, or a random id is rejected before any answer is persisted. A
     * request needs at least one meaningful input (a valid selected option or
     * non-blank free text), and free text is rejected when the node does not
     * allow free-form answers. Both inputs are preserved in the immutable
     * answer when the node permits them.
     *
     * <p>The full path stays identical: validate the selected option, persist
     * the immutable answer, interpret it, draft and ground the answer patch,
     * reflect it, persist the patch, draft and persist the next node.
     */
    public AnswerRunResult answerActiveNodeAndDraftNext(UUID projectId,
                                                            UUID selectedOptionId,
                                                            String freeText) {
        Project project = loadProject(projectId);
        Route route = loadActiveRoute(project);
        if (route.tipNodeId() == null) {
            throw new IllegalStateException("Active route has no tip node: " + route.id());
        }
        Node tipNode = nodeService.getNode(route.tipNodeId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Active tip node not found: " + route.tipNodeId()));

        String selectedOption = validateSelectedOption(tipNode, selectedOptionId);
        String normalizedFreeText = normalizeFreeText(freeText);
        validateAnswerInput(tipNode, selectedOption, normalizedFreeText);

        AgentRun run = agentRunService.create(
                projectId,
                route.id(),
                AgentRunTriggerType.ANSWER_NODE,
                tipNode.id(),
                null);
        String trace = "created";

        try {
            trace = appendTrace(trace, "context_built");
            ContextSnapshot contextSnapshot = buildAndValidateContext(run, projectId, trace);

            Answer answer = answerService.finalizeAnswer(
                    projectId, route.id(), tipNode.id(), selectedOption,
                    normalizedFreeText, "user");
            return continueAfterAnswer(run, contextSnapshot, answer, trace);
        } catch (RuntimeException ex) {
            failIfNotTerminal(run.id(), trace, ex);
            throw ex;
        }
    }

    /**
     * Resumes patch and next-node processing for an existing immutable answer.
     *
     * <p>This is the repair path for a failed answer-processing run: the answer
     * was already persisted, so it is never finalized again and never
     * overwritten. The run records the existing answer id as its produced
     * answer id. The answer must belong to this project, to the active route,
     * and to the active route's tip node.
     */
    public AnswerRunResult repairAnswerProcessingAndDraftNext(UUID projectId, UUID answerId) {
        Project project = loadProject(projectId);
        Route route = loadActiveRoute(project);

        Answer answer = answerService.getAnswer(answerId)
                .orElseThrow(() -> new IllegalArgumentException("Answer not found: " + answerId));
        if (!answer.projectId().equals(projectId)) {
            throw new IllegalArgumentException(
                    "Answer " + answerId + " does not belong to project " + projectId);
        }
        if (!answer.routeId().equals(route.id())) {
            throw new IllegalStateException(
                    "Answer " + answerId + " does not belong to the active route");
        }
        if (!answer.nodeId().equals(route.tipNodeId())) {
            throw new IllegalStateException("Answer node is not the active route tip");
        }

        AgentRun run = agentRunService.create(
                projectId,
                route.id(),
                AgentRunTriggerType.ANSWER_NODE,
                answer.nodeId(),
                null);
        String trace = "created";

        try {
            trace = appendTrace(trace, "context_built");
            ContextSnapshot contextSnapshot = buildAndValidateContext(run, projectId, trace);
            return continueAfterAnswer(run, contextSnapshot, answer, trace);
        } catch (RuntimeException ex) {
            failIfNotTerminal(run.id(), trace, ex);
            throw ex;
        }
    }

    /**
     * Runs the spec loop against the active route's tip: draft a spec
     * through the model gateway, validate grounding through
     * {@link SpecGroundingGate}, verify every source reference through
     * {@link SpecSourceReferenceGuard}, convert the draft to the existing
     * {@link SpecSection} / {@link SourceReference} / {@link UnresolvedItem}
     * model, and persist a derived {@link SpecSnapshot}.
     *
     * <p>If either gate rejects the draft, no spec snapshot is persisted and the
     * run is marked FAILED.
     */
    public SpecRunResult generateSpec(UUID projectId) {
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
        String trace = "created";

        try {
            trace = appendTrace(trace, "context_built");
            ContextSnapshot contextSnapshot = buildAndValidateContext(run, projectId, trace);

            trace = appendTrace(trace, "model_called:" + AgentTaskType.DRAFT_SPEC.name());
            ModelResponse response = callModel(run, contextSnapshot, trace,
                    AgentTaskType.DRAFT_SPEC,
                    projectionBuilder.buildInputJson(contextSnapshot, Map.of()),
                    AgentAction.GENERATE_SPEC,
                    "Expected GENERATE_SPEC from DRAFT_SPEC");
            SpecDraft specDraft = structuredOutputMapper.toSpecDraft(
                    structuredModelOutputParser.parse(AgentTaskType.DRAFT_SPEC, response.outputJson()));

            ReflectionResult grounding = specGroundingGate.validate(specDraft);
            trace = appendTrace(trace, "reflected:SPEC_GROUNDING");
            agentRunService.markReflected(run.id(), trace);
            if (!grounding.accepted()) {
                agentRunService.fail(run.id(), appendTrace(trace, "failed:spec_grounding_rejected"));
                throw new ModelContractException("Spec grounding rejected spec draft");
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

            ReflectionResult sourceRefsReflection = specSourceReferenceGuard.validate(
                    projectId, route.id(), contextSnapshot, sourceRefs);
            trace = appendTrace(trace, "reflected:SOURCE_REFERENCES");
            agentRunService.markReflected(run.id(), trace);
            if (!sourceRefsReflection.accepted()) {
                agentRunService.fail(run.id(), appendTrace(trace, "failed:source_references_rejected"));
                throw new ModelContractException("Spec source reference guard rejected spec draft");
            }

            SpecSnapshot snapshot = specSnapshotService.createSnapshot(
                    projectId, route.id(), route.tipNodeId(), contextSnapshot.id(),
                    "markdown", sections, unresolvedItems, sourceRefs, run.id());
            trace = appendTrace(trace, "persisted_spec_snapshot");
            agentRunService.markPersistedSpecSnapshot(run.id(), snapshot.id(), trace);
            trace = appendTrace(trace, "completed");
            agentRunService.complete(run.id(), AgentRunStatus.COMPLETED, trace);

            AgentRun completedRun = agentRunService.getRun(run.id()).orElseThrow();
            return new SpecRunResult(completedRun, contextSnapshot, response, snapshot);
        } catch (RuntimeException ex) {
            failIfNotTerminal(run.id(), trace, ex);
            throw ex;
        }
    }

    /**
     * Generates a replacement question through the generic DRAFT_NODE
     * capability. No route/node is created until the proposal passes every
     * model and runtime gate.
     */
    public ReplacementRunResult replaceQuestion(UUID projectId,
                                                UUID sourceRouteId,
                                                UUID targetNodeId,
                                                String userDirection) {
        Project project = loadProject(projectId);
        routeService.requireExplorationSource(projectId, sourceRouteId);
        Node targetNode = nodeService.getNode(targetNodeId)
                .orElseThrow(() -> new IllegalArgumentException("Target node not found: " + targetNodeId));
        if (!targetNode.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Target node does not belong to project");
        }
        if (targetNode.parentNodeId() == null) {
            throw new IllegalStateException("Root node replacement is not supported");
        }

        ContextSnapshot contextSnapshot = contextBuilder.buildForReplacement(
                projectId, sourceRouteId, targetNodeId);
        AgentRun run = agentRunService.create(
                projectId, sourceRouteId, AgentRunTriggerType.REGENERATE_NODE,
                targetNodeId, null);
        String trace = "created";
        try {
            trace = appendTrace(trace, "context_built");
            agentRunService.attachContext(run.id(), contextSnapshot.id(), trace);
            ReflectionResult contextReflection = contextGuard.validate(contextSnapshot);
            if (!contextReflection.accepted()) {
                agentRunService.fail(run.id(), appendTrace(trace, "failed:context_guard_rejected"));
                throw new ModelContractException("Replacement context rejected");
            }

            trace = appendTrace(trace, "model_called:" + AgentTaskType.DRAFT_NODE.name());
            ModelResponse response = callModel(
                    run, contextSnapshot, trace, AgentTaskType.DRAFT_NODE,
                    projectionBuilder.buildInputJson(contextSnapshot,
                            projectionBuilder.redirectedNodeTaskInput(userDirection)),
                    AgentAction.ASK_NEXT_QUESTION,
                    "Expected ASK_NEXT_QUESTION from replacement DRAFT_NODE");
            NodeDraft draft = structuredOutputMapper.toNodeDraft(
                    structuredModelOutputParser.parse(AgentTaskType.DRAFT_NODE, response.outputJson()));
            ReflectionResult nodeReflection = nodeReflectionGate.validate(draft);
            trace = appendTrace(trace, "reflected:NODE");
            agentRunService.markReflected(run.id(), trace);
            if (!nodeReflection.accepted()) {
                agentRunService.fail(run.id(), appendTrace(trace, "failed:node_reflection_rejected"));
                throw new ModelContractException("Replacement node reflection rejected");
            }
            if (normalize(draft.question()).equals(normalize(targetNode.question()))) {
                agentRunService.fail(run.id(), appendTrace(trace, "failed:duplicate_question"));
                throw new ModelContractException("Replacement question must differ from the rejected question");
            }

            RegenerateResult replacement = routeService.commitReplacementFromNode(
                    projectId, sourceRouteId, targetNodeId, null,
                    draft.question(), draft.purpose(), draft.options(), draft.allowFreeAnswer());
            trace = appendTrace(trace, "persisted_node");
            agentRunService.markPersistedNode(run.id(), replacement.replacementNode().id(), trace);
            trace = appendTrace(trace, "completed");
            agentRunService.complete(run.id(), AgentRunStatus.COMPLETED, trace);
            AgentRun completedRun = agentRunService.getRun(run.id()).orElseThrow();
            return new ReplacementRunResult(completedRun, contextSnapshot, response, replacement);
        } catch (RuntimeException ex) {
            failIfNotTerminal(run.id(), trace, ex);
            throw ex;
        }
    }

    /**
     * Shared post-answer processing: interpret the answer, draft and ground the
     * answer patch, persist the patch, then draft and persist the next node.
     * The answer is already immutable by the time this runs; the patch claims
     * are grounded with the real answered node and answer ids before the
     * reflection gate sees them.
     */
    private AnswerRunResult continueAfterAnswer(AgentRun run,
                                                    ContextSnapshot contextSnapshot,
                                                    Answer answer,
                                                    String trace) {
        UUID answeredNodeId = answer.nodeId();

        trace = appendTrace(trace, "persisted_answer");
        agentRunService.markPersistedAnswer(run.id(), answer.id(), trace);

        AnswerPatch patch = answerPatchService.findBySourceAnswerId(answer.id()).orElse(null);
        ModelResponse interpretResponse;
        ModelResponse patchResponse;
        if (patch == null) {
            trace = appendTrace(trace, "model_called:" + AgentTaskType.INTERPRET_ANSWER.name());
            interpretResponse = callModel(run, contextSnapshot, trace,
                    AgentTaskType.INTERPRET_ANSWER,
                    projectionBuilder.buildInputJson(contextSnapshot,
                            projectionBuilder.answerTaskInput(answer)),
                    AgentAction.INTERPRET_ANSWER,
                    "Expected INTERPRET_ANSWER from INTERPRET_ANSWER");
            AnswerInterpretationResult interpretation = structuredOutputMapper.toInterpretation(
                    structuredModelOutputParser.parse(AgentTaskType.INTERPRET_ANSWER,
                            interpretResponse.outputJson()));

            trace = appendTrace(trace, "model_called:" + AgentTaskType.DRAFT_ANSWER_PATCH.name());
            patchResponse = callModel(run, contextSnapshot, trace,
                    AgentTaskType.DRAFT_ANSWER_PATCH,
                    projectionBuilder.buildInputJson(contextSnapshot,
                            projectionBuilder.interpretationTaskInput(answer, interpretation)),
                    AgentAction.INTERPRET_ANSWER,
                    "Expected INTERPRET_ANSWER from DRAFT_ANSWER_PATCH");
            AnswerPatchDraft patchDraft = structuredOutputMapper.toPatchDraft(
                    structuredModelOutputParser.parse(AgentTaskType.DRAFT_ANSWER_PATCH,
                            patchResponse.outputJson()));

            // The model never fabricates real source ids. The runtime grounds
            // confirmed claims with the real answered node and answer before
            // the patch may enter requirement state.
            AnswerPatchDraft groundedDraft = withRealSources(patchDraft, answeredNodeId, answer.id());
            ReflectionResult patchReflection = patchReflectionGate.validate(groundedDraft);
            trace = appendTrace(trace, "reflected:PATCH");
            agentRunService.markReflected(run.id(), trace);
            if (!patchReflection.accepted()) {
                agentRunService.fail(run.id(), appendTrace(trace, "failed:patch_reflection_rejected"));
                throw new ModelContractException("Patch reflection rejected answer patch draft");
            }

            patch = answerPatchService.save(
                    run.projectId(), run.routeId(), answeredNodeId, answer.id(),
                    groundedDraft.claims(), run.id());
            trace = appendTrace(trace, "persisted_patch");
            agentRunService.markPersistedAnswerPatch(run.id(), patch.id(), trace);
        } else {
            trace = appendTrace(trace, "reused_persisted_patch:" + patch.id());
            agentRunService.markPersistedAnswerPatch(run.id(), patch.id(), trace);
            interpretResponse = skippedModelResponse(run, contextSnapshot,
                    AgentTaskType.INTERPRET_ANSWER, AgentAction.INTERPRET_ANSWER,
                    "persisted_patch_reuse");
            patchResponse = skippedModelResponse(run, contextSnapshot,
                    AgentTaskType.DRAFT_ANSWER_PATCH, AgentAction.INTERPRET_ANSWER,
                    "persisted_patch_reuse");
        }

        trace = appendTrace(trace, "model_called:" + AgentTaskType.DRAFT_NODE.name());
        ModelResponse nodeResponse = callModel(run, contextSnapshot, trace,
                AgentTaskType.DRAFT_NODE,
                projectionBuilder.buildInputJson(contextSnapshot,
                        projectionBuilder.afterAnswerNodeTaskInput(answer, patch)),
                AgentAction.ASK_NEXT_QUESTION,
                "Expected ASK_NEXT_QUESTION from DRAFT_NODE");
        NodeDraft draft = structuredOutputMapper.toNodeDraft(
                structuredModelOutputParser.parse(AgentTaskType.DRAFT_NODE, nodeResponse.outputJson()));

        ReflectionResult nodeReflection = nodeReflectionGate.validate(draft);
        trace = appendTrace(trace, "reflected:NODE");
        agentRunService.markReflected(run.id(), trace);
        if (!nodeReflection.accepted()) {
            agentRunService.fail(run.id(), appendTrace(trace, "failed:node_reflection_rejected"));
            throw new ModelContractException("Node reflection rejected node draft");
        }

        Node producedNode = nodeService.createChildNode(
                run.projectId(), run.routeId(), answeredNodeId,
                draft.question(), draft.purpose(), draft.options(), draft.allowFreeAnswer());
        trace = appendTrace(trace, "persisted_node");
        agentRunService.markPersistedNode(run.id(), producedNode.id(), trace);
        trace = appendTrace(trace, "completed");
        agentRunService.complete(run.id(), AgentRunStatus.COMPLETED, trace);

        AgentRun completedRun = agentRunService.getRun(run.id()).orElseThrow();
        return new AnswerRunResult(completedRun, contextSnapshot,
                interpretResponse, patchResponse, nodeResponse, answer, patch, producedNode);
    }

    private ModelResponse skippedModelResponse(AgentRun run,
                                                ContextSnapshot contextSnapshot,
                                                AgentTaskType taskType,
                                                AgentAction action,
                                                String reason) {
        return new ModelResponse(run.id(), contextSnapshot.id(), taskType, action, "{}",
                Map.of("skipped", reason));
    }

    private Project loadProject(UUID projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
    }

    /**
     * Validates a client-selected option id against the exact active node. The
     * client may only reference an existing runtime-owned option id previously
     * returned by the active node; ids from other nodes, sibling routes, or
     * random fabrication are rejected. Returns the runtime-owned option id
     * string, or {@code null} when no option was selected.
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

    private String normalizeFreeText(String freeText) {
        return (freeText == null || freeText.isBlank()) ? null : freeText;
    }

    /**
     * Enforces the answer input policy: at least one meaningful input (a valid
     * selected option or non-blank free text) is required, and non-blank free
     * text is rejected when the node does not allow free-form answers.
     */
    private void validateAnswerInput(Node tipNode, String selectedOption, String freeText) {
        if (selectedOption == null && freeText == null) {
            throw new IllegalArgumentException("Answer requires a selected option or free text");
        }
        if (freeText != null && !tipNode.allowFreeAnswer()) {
            throw new IllegalArgumentException("This node does not allow free-form answers");
        }
    }

    private Route loadActiveRoute(Project project) {
        if (project.activeRouteId() == null) {
            throw new IllegalStateException("Project has no active route: " + project.id());
        }
        return routeRepository.findById(project.activeRouteId())
                .orElseThrow(() -> new IllegalArgumentException("Active route not found: " + project.activeRouteId()));
    }

    private ContextSnapshot buildAndValidateContext(AgentRun run, UUID projectId, String trace) {
        ContextSnapshot contextSnapshot = contextBuilder.buildFromActiveRoute(
                projectId, run.id(), ContextOperationType.NORMAL);
        agentRunService.attachContext(run.id(), contextSnapshot.id(), trace);

        ReflectionResult contextReflection = contextGuard.validate(contextSnapshot);
        if (!contextReflection.accepted()) {
            agentRunService.fail(run.id(), appendTrace(trace, "failed:context_guard_rejected"));
            throw new ModelContractException("Context guard rejected agent run");
        }
        return contextSnapshot;
    }

    private ModelResponse callModel(AgentRun run,
                                    ContextSnapshot contextSnapshot,
                                    String trace,
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
                Map.of());

        ModelResponse response;
        try {
            response = modelGateway.run(request);
        } catch (ModelGatewayException ex) {
            // The gateway rejected the call before any response came back.
            // Persist the trace (which already carries model_called:<task>) so
            // the FAILED run stays attributable to its task; the safe
            // provider-neutral failure category is appended by
            // failIfNotTerminal.
            agentRunService.markModelCalled(run.id(), trace);
            throw ex;
        }
        agentRunService.markModelCalled(run.id(), trace);

        // Gateway output is untrusted input. The runtime owns correlation
        // validation: a response that does not echo back the exact requested
        // agentRunId / contextSnapshotId / taskType is rejected before any
        // typed parsing or reflection may happen.
        ModelResponseCorrelation.validate(request, response);

        if (response.action() != expectedAction) {
            agentRunService.fail(run.id(), appendTrace(trace, "failed:unexpected_action"));
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
     * by the model gateway. The model may only reference runtime records it knows
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

    private String appendTrace(String trace, String step) {
        return trace == null || trace.isBlank() ? step : trace + "\n" + step;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase();
    }

    /**
     * Marks the run FAILED in its own transaction unless it already reached a
     * terminal state. The cumulative trace always ends with the failure step so
     * failed runs remain diagnosable.
     *
     * <p>The trace parameter is the caller's local view, which is truncated when
     * the exception is thrown from a helper (for example from the shared
     * {@link #continueAfterAnswer} post-answer processing). The run itself
     * carries the last persisted cumulative trace, so the failure step is
     * appended to that instead of the truncated local view.
     */
    private void failIfNotTerminal(UUID runId, String trace, RuntimeException ex) {
        AgentRun latest = agentRunService.getRun(runId).orElse(null);
        if (latest != null && latest.status() != AgentRunStatus.FAILED
                && latest.status() != AgentRunStatus.COMPLETED) {
            String persisted = latest.trace();
            String base = (persisted == null || persisted.isBlank()) ? trace : persisted;
            agentRunFailureService.fail(runId, appendTrace(base, failureStepFor(ex)));
        }
    }

    /**
     * Maps an unexpected exception to a safe trace step. Gateway failures
     * record their provider-neutral category (never the message, which could
     * echo provider payloads); everything else records the exception class
     * name.
     */
    private String failureStepFor(RuntimeException ex) {
        if (ex instanceof ModelGatewayException modelException) {
            return "failed:provider:" + modelException.gatewayCategory();
        }
        return "failed:" + ex.getClass().getSimpleName();
    }
}
