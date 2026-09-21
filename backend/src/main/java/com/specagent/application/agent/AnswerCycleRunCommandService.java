package com.specagent.application.agent;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunRequestFingerprint;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.runevent.RunProgressAssembler;
import com.specagent.agent.runevent.RunProgressView;
import com.specagent.agent.runtime.AnswerProcessingGate;
import com.specagent.agent.runtime.RunService;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerService;
import com.specagent.application.support.CommandExecution;
import com.specagent.common.ApiException;
import com.specagent.node.NodeKind;
import com.specagent.node.NodeService;
import com.specagent.project.ProjectService;
import com.specagent.route.RouteHistoryResolver;
import com.specagent.route.RouteService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * Use-case orchestration for the async agent-run command API: idempotent
 * replay, per-operation dispatch, and the ANSWER_TIP → RESUME_ANSWER rewrite
 * when the target node was already answered. The controller stays a thin
 * translation layer between HTTP and this service.
 *
 * <p>It lives in {@code com.specagent.application.agent}: the orchestration is
 * application logic composed of nine runtime services, not HTTP translation.
 */
@Service
public class AnswerCycleRunCommandService {

    private final RunService runService;
    private final AgentRunService agentRunService;
    private final AgentRunEventService eventService;
    private final AnswerService answerService;
    private final AnswerProcessingGate answerProcessingGate;
    private final RouteService routeService;
    private final ProjectService projectService;
    private final NodeService nodeService;
    private final com.specagent.agent.loop.AgentRunChainReadService chainReadService;
    private final RunProgressAssembler runProgressAssembler;
    private final RouteHistoryResolver routeHistoryResolver;

    public AnswerCycleRunCommandService(RunService runService,
                                        AgentRunService agentRunService,
                                        AgentRunEventService eventService,
                                        AnswerService answerService,
                                        AnswerProcessingGate answerProcessingGate,
                                        RouteService routeService,
                                        ProjectService projectService,
                                        NodeService nodeService,
                                        com.specagent.agent.loop.AgentRunChainReadService chainReadService,
                                        RunProgressAssembler runProgressAssembler,
                                        RouteHistoryResolver routeHistoryResolver) {
        this.runService = runService;
        this.agentRunService = agentRunService;
        this.eventService = eventService;
        this.answerService = answerService;
        this.answerProcessingGate = answerProcessingGate;
        this.routeService = routeService;
        this.projectService = projectService;
        this.nodeService = nodeService;
        this.chainReadService = chainReadService;
        this.runProgressAssembler = runProgressAssembler;
        this.routeHistoryResolver = routeHistoryResolver;
    }

    public ResponseEntity<AcceptedRunView> createRun(UUID projectId, CreateRunRequest request) {
        String operation = request.operation();
        String idempotencyKey = request.idempotencyKey();
        // The FULL multi-select list is part of the logical request identity:
        // two requests that agree only on the first option but differ in the
        // rest of the selection are different answers and must conflict on
        // the same idempotency key instead of silently replaying.
        //
        // Normalization (mirrors the execution semantics in RunService, where
        // selectedOptionIds is the authoritative selection in user order and
        // selectedOptionId stays the legacy first-selection field):
        // - selectedOptionIds present and non-empty  → used verbatim (user order).
        // - otherwise selectedOptionId != null       → single-select, [selectedOptionId]
        //   (same derivation the legacy fingerprint overload always applied, so
        //   persisted single-select runs keep replaying).
        // - both absent                              → null (free-text / no selection).
        // An empty selectedOptionIds list carries no selection semantics and is
        // normalized to null.
        List<UUID> effectiveOptionIds =
                request.selectedOptionIds() != null && !request.selectedOptionIds().isEmpty()
                        ? request.selectedOptionIds()
                        : (request.selectedOptionId() != null
                                ? List.of(request.selectedOptionId())
                                : null);
        String requestFingerprint = AgentRunRequestFingerprint.forClientRequest(
                projectId, operation, request.nodeId(), request.sourceRouteId(),
                request.answerId(), request.selectedOptionId(), effectiveOptionIds,
                request.freeText(), request.persistenceIntent());

        var replay = agentRunService.findIdempotentReplay(
                projectId, idempotencyKey, requestFingerprint);
        if (replay.isPresent()) {
            return acceptedRun(replay.get());
        }

        // 可选显式路线：本次 run 从此只认这条路线，而不是项目唯一的 Active 指针。
        // 这是"多条路线各自独立生成/回答"的入口 —— 路由归属由端点校验（同项目 + OPEN
        // 在 RunService 里做），未提供时下面每一条分支都退回原有的 Active 语义。
        UUID explicitRouteId = request.sourceRouteId();
        if (explicitRouteId != null) {
            CommandExecution.requireRouteInProject(
                    projectService, routeService, projectId, explicitRouteId);
        }

        if ("DRAFT_QUESTION".equals(operation)) {
            if (explicitRouteId == null) {
                requireActiveRoute(projectId);
            }
            // 前置资格检查：路线 tip 是"未回答的问题"时，起草注定被
            // UNANSWERED_QUESTION_HAS_CHILD 不变式拒绝（决策的追加动作
            // 无法执行）。在入队前 fail fast，避免用户等完一次模型调用
            // 只收到一个失败运行。
            UUID draftTargetRouteId = explicitRouteId != null
                    ? explicitRouteId : runService.getActiveRouteId(projectId);
            var draftRoute = routeService.getRoute(draftTargetRouteId)
                    .orElseThrow(() -> ApiException.notFound(
                            "ROUTE_NOT_FOUND", "Route not found"));
            if (draftRoute.tipNodeId() != null) {
                requireRouteAnswersProcessed(draftTargetRouteId, draftRoute.tipNodeId());
                var tipNode = nodeService.getNode(draftRoute.tipNodeId())
                        .orElseThrow(() -> ApiException.notFound(
                                "NODE_NOT_FOUND", "Node not found"));
                if (NodeKind.INTERACTION.equals(tipNode.kind())
                        && !tipHasEffectiveAnswer(draftTargetRouteId, draftRoute.tipNodeId())) {
                    throw ApiException.conflict(
                            "UNANSWERED_QUESTION_HAS_CHILD",
                            "The current question has no finalized answer yet; answer it before drafting the next question");
                }
            }
            return acceptedRun(runService.createQueuedDraftQuestion(
                    projectId, idempotencyKey, requestFingerprint, explicitRouteId));
        }

        if ("GENERATE_ARTIFACT".equals(operation)) {
            if (explicitRouteId == null) {
                requireActiveRoute(projectId);
            }
            UUID targetRouteId = explicitRouteId != null
                    ? explicitRouteId : runService.getActiveRouteId(projectId);
            var route = routeService.getRoute(targetRouteId)
                    .orElseThrow(() -> ApiException.notFound(
                            "ROUTE_NOT_FOUND", "Route not found"));
            if (route.tipNodeId() == null) {
                throw ApiException.conflict(
                        "NO_ACTIVE_TIP_NODE",
                        "The active route has no tip node to generate a spec from");
            }
            requireTipAnswerProcessed(targetRouteId, route.tipNodeId());
            return acceptedRun(runService.createQueuedArtifactGeneration(
                    projectId, idempotencyKey, requestFingerprint, explicitRouteId));
        }

        if ("REGENERATE_NODE".equals(operation)) {
            if (request.nodeId() == null || request.sourceRouteId() == null) {
                throw ApiException.badRequest(
                        "REGENERATE_TARGET_REQUIRED",
                        "Replacement requires an explicit source route and node");
            }
            CommandExecution.requireProject(projectService, projectId);
            var target = CommandExecution.requireNodeInProject(
                    projectService, nodeService, projectId, request.nodeId());
            if (target.parentNodeId() == null) {
                throw ApiException.conflict(
                        "REGENERATE_ROOT_NOT_SUPPORTED",
                        "Root node regeneration is not supported");
            }
            return acceptedRun(runService.createQueuedRegenerate(
                    projectId, request.sourceRouteId(), request.nodeId(),
                    request.freeText(), idempotencyKey, requestFingerprint));
        }

        if ("ANSWER_TIP".equals(operation) || "RESUME_ANSWER".equals(operation)) {
            if (explicitRouteId == null) {
                requireActiveRoute(projectId);
            }
        }

        UUID answerId = request.answerId();
        if (isAnswerOperation(operation)) {
            UUID targetRouteId = explicitRouteId != null
                    ? explicitRouteId : runService.getActiveRouteId(projectId);
            // Identity first, guards second — regardless of whether the optional
            // nodeId was sent. An answerId alone already identifies the target,
            // so letting the guards below depend on nodeId being present let a
            // request replay the persisted answer while silently discarding the
            // newly submitted content (the loss this fix closes).
            Answer persisted =
                    resolveAnswerOperationTarget(projectId, targetRouteId, request);
            if (persisted != null) {
                UUID tipNodeId = routeService.getRoute(targetRouteId)
                        .orElseThrow(() -> ApiException.notFound(
                                "ROUTE_NOT_FOUND", "Route not found"))
                         .tipNodeId();
                // Recovery reuses the existing Answer and its checkpoint, so it
                // must be the SAME submission even when the answer is already
                // historical. Accepting new content would silently discard it.
                if (submittedContentDiffers(persisted, effectiveOptionIds, request.freeText())) {
                    throw ApiException.conflict(
                            "ANSWER_CONTENT_MISMATCH",
                            "The node already carries a finalized answer and the submitted"
                                    + " content differs from it; retry the saved answer"
                                    + " (RESUME_ANSWER) or answer the next question");
                }
                // A legacy/previously queued answer may have already lost tip
                // position because a later question was drafted. It remains
                // recoverable on its owning route, but only when it is still
                // part of that route's immutable lineage. Historical recovery
                // never replays the later DECISION or moves the route tip.
                if (!persisted.nodeId().equals(tipNodeId)
                        && !routeHistoryResolver.resolveLineage(tipNodeId)
                                .contains(persisted.nodeId())) {
                    throw ApiException.conflict(
                            "ANSWER_ALREADY_FINALIZED",
                            "The answered node is not part of the target route history");
                }
                operation = "RESUME_ANSWER";
                answerId = persisted.id();
            }
        }

        AgentRun run = runService.createQueuedRunWithInputResultForRoute(
                projectId, operation, request.nodeId(),
                request.selectedOptionId(), request.selectedOptionIds(),
                request.freeText(), answerId,
                idempotencyKey, requestFingerprint, request.persistenceIntent(), explicitRouteId);
        return acceptedRun(run);
    }

    /**
     * True when the route tip question carries an <em>effective</em> answer on
     * that route — its own Answer or one inherited from the route it branched
     * off (see {@code route_inherited_answers}).
     *
     * <p>The pre-check must resolve answers exactly the way the invariant it
     * predicts does ({@code GraphInvariantValidator.validateQuestionCanHaveChild}
     * uses {@link RouteHistoryResolver#resolveEffectiveAnswerRefs}). A route-local
     * lookup misses inherited answers, so a freshly forked route — whose tip is
     * by construction a shared node answered on the source route — was rejected
     * with a false {@code UNANSWERED_QUESTION_HAS_CHILD} before the Draft could
     * even be queued.
     */
    private boolean tipHasEffectiveAnswer(UUID routeId, UUID tipNodeId) {
        List<UUID> lineage = routeHistoryResolver.resolveLineage(tipNodeId);
        return routeHistoryResolver.resolveEffectiveAnswerRefs(routeId, lineage).stream()
                .anyMatch(ref -> ref.nodeId().equals(tipNodeId));
    }

    private static boolean isAnswerOperation(String operation) {
        return "ANSWER_TIP".equals(operation) || "RESUME_ANSWER".equals(operation);
    }

    /**
     * Resolves the persisted answer an answer operation targets, rejecting
     * inconsistent identities instead of skipping validation.
     *
     * <p>Identity precedence: a request {@code answerId} is authoritative — it
     * must exist in this project, must agree with the optional
     * {@code nodeId}, and must belong to the target route (a resumable answer
     * lives on its own route; pass that route as {@code sourceRouteId} to
     * recover it). Without an {@code answerId}, the route-local answer for the
     * optional {@code nodeId} is used; a null {@code nodeId} falls back to the
     * target route's tip, mirroring what the queued run would answer.
     *
     * <p>An empty result means no persisted answer is involved and the request
     * proceeds on the fresh-submission path with all its normal eligibility
     * checks at execution time.
     */
    private Answer resolveAnswerOperationTarget(UUID projectId, UUID targetRouteId,
                                                CreateRunRequest request) {
        if (request.answerId() != null) {
            Answer answer = CommandExecution.requireAnswerInProject(
                    projectService, answerService, projectId, request.answerId());
            if (!answer.routeId().equals(targetRouteId)) {
                throw ApiException.conflict(
                        "ANSWER_ROUTE_MISMATCH",
                        "The referenced answer belongs to another route;"
                                + " resume it on its own route (send its sourceRouteId)");
            }
            if (request.nodeId() != null && !request.nodeId().equals(answer.nodeId())) {
                throw ApiException.conflict(
                        "ANSWER_TARGET_MISMATCH",
                        "The referenced answer and the submitted nodeId"
                                + " point at different nodes");
            }
            return answer;
        }
        UUID nodeId = request.nodeId() != null
                ? request.nodeId()
                : routeService.getRoute(targetRouteId)
                        .orElseThrow(() -> ApiException.notFound(
                                "ROUTE_NOT_FOUND", "Route not found"))
                        .tipNodeId();
        if (nodeId == null) {
            return null;
        }
        return answerService.findAnswerForNode(targetRouteId, nodeId).orElse(null);
    }

    /**
     * Whether a submission actually supplies content that differs from the
     * answer already persisted for the node.
     *
     * <p>A submission with no content at all is a pure retry and never counts as
     * differing; a submission that carries content must match the persisted
     * answer exactly (the full selection, in user order, plus the normalized
     * free text) to be resumed. Anything else is a request to change an
     * immutable answer and is rejected instead of silently dropped.
     */
    private boolean submittedContentDiffers(Answer persisted, List<UUID> submittedOptionIds,
                                            String submittedFreeText) {
        List<UUID> submittedOptions = submittedOptionIds == null ? List.of() : submittedOptionIds;
        String normalizedFreeText = normalizeAnswerFreeText(submittedFreeText);
        if (submittedOptions.isEmpty() && normalizedFreeText == null) {
            return false;
        }
        List<String> persistedOptions = persisted.selectedOptionIds() == null
                ? List.of() : persisted.selectedOptionIds().stream().map(String::valueOf).toList();
        List<String> submitted = submittedOptions.stream().map(String::valueOf).toList();
        return !persistedOptions.equals(submitted)
                || !java.util.Objects.equals(
                        normalizeAnswerFreeText(persisted.freeText()), normalizedFreeText);
    }

    private String normalizeAnswerFreeText(String freeText) {
        return freeText == null || freeText.isBlank() ? null : freeText;
    }

    /**
     * Refuses artifact generation while any answer the spec context would
     * actually use still owes its STATE_UPDATE checkpoint.
     *
     * <p>The judge is the shared {@link AnswerProcessingGate} over the route's
     * effective answer history (route-local answers plus the inherited
     * prefix) — the same set {@code ContextBuilder} folds into the spec
     * context. The previous tip-only route-local lookup missed the inherited
     * case: forking from an answered node whose STATE_UPDATE never completed
     * inherited that answer into the branch context while this gate let the
     * generation through.
     */
    private void requireTipAnswerProcessed(UUID routeId, UUID tipNodeId) {
        answerProcessingGate.firstUnprocessedAnswer(routeId, tipNodeId)
                .ifPresent(pending -> {
                    throw ApiException.conflict(
                            "ANSWER_CYCLE_INCOMPLETE",
                            "Answer processing is incomplete; retry the saved answer"
                                    + " on its owning route before generating a spec",
                            Map.of(
                                    "answerId", pending.id().toString(),
                                    "routeId", pending.routeId().toString(),
                                    "nodeId", pending.nodeId().toString()));
                });
    }

    /**
     * DRAFT_QUESTION advances the route tip. It is therefore not allowed to
     * cross any effective answer whose STATE_UPDATE checkpoint is missing.
     * The same judge is repeated by DecisionCycleService and the graph
     * invariant boundary for queued/racing executions.
     */
    private void requireRouteAnswersProcessed(UUID routeId, UUID tipNodeId) {
        answerProcessingGate.firstUnprocessedAnswer(routeId, tipNodeId)
                .ifPresent(pending -> {
                    throw ApiException.conflict(
                            "ANSWER_CYCLE_INCOMPLETE",
                            "Answer processing is incomplete; retry the saved answer"
                                    + " before drafting the next question",
                            Map.of(
                                    "answerId", pending.id().toString(),
                                    "routeId", pending.routeId().toString(),
                                    "nodeId", pending.nodeId().toString()));
                });
    }

    private void requireActiveRoute(UUID projectId) {
        CommandExecution.requireActiveRoute(
                CommandExecution.requireProject(projectService, projectId),
                routeService);
    }

    private ResponseEntity<AcceptedRunView> acceptedRun(AgentRun run) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(AcceptedRunView.from(run, latestPhaseCode(run.id())));
    }

    public AgentRunViewResponse runView(AgentRun run) {
        var chain = chainReadService.read(run.id());
        RunProgressView progress = runProgressAssembler.assemble(run.id());
        return AgentRunViewResponse.from(run, latestPhaseCode(run.id()), chain, progress);
    }

    private String latestPhaseCode(UUID runId) {
        List<AgentRunEvent> events = eventService.findByRunId(runId);
        return events.stream()
                .reduce((first, second) -> second)
                .map(event -> event.phase().code())
                .orElse(AgentRunPhase.CREATED.code());
    }
}
