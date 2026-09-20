package com.specagent.application.agent;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunRequestFingerprint;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.runevent.RunProgressAssembler;
import com.specagent.agent.runevent.RunProgressView;
import com.specagent.agent.runtime.RunService;
import com.specagent.answer.AnswerService;
import com.specagent.application.support.CommandExecution;
import com.specagent.common.ApiException;
import com.specagent.node.NodeKind;
import com.specagent.node.NodeService;
import com.specagent.project.ProjectService;
import com.specagent.route.RouteHistoryResolver;
import com.specagent.route.RouteService;
import java.util.List;
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
        if ("ANSWER_TIP".equals(operation) && request.nodeId() != null && answerId == null) {
            UUID targetRouteId = explicitRouteId != null
                    ? explicitRouteId : runService.getActiveRouteId(projectId);
            boolean answerExists = answerService.existsAnswerFor(targetRouteId, request.nodeId());
            if (answerExists) {
                UUID tipNodeId = routeService.getRoute(targetRouteId)
                        .orElseThrow(() -> ApiException.notFound(
                                "ROUTE_NOT_FOUND", "Route not found"))
                        .tipNodeId();
                if (!request.nodeId().equals(tipNodeId)) {
                    throw ApiException.conflict(
                            "ANSWER_ALREADY_FINALIZED",
                            "The active node has already been answered");
                }
                operation = "RESUME_ANSWER";
                answerId = answerService.findAnswerForNode(targetRouteId, request.nodeId())
                        .map(a -> a.id()).orElse(null);
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
