package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunRequestFingerprint;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runevent.AgentRunEvent;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.runevent.RunProgressAssembler;
import com.specagent.agent.runevent.RunProgressView;
import com.specagent.agent.runtime.AnswerProcessingGate;
import com.specagent.agent.runtime.RunService;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.route.CommandExecution;
import com.specagent.common.ApiException;
import com.specagent.workspace.node.NodeKind;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.RouteHistoryResolver;
import com.specagent.workspace.route.RouteService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

/**
 * 文件名:AnswerCycleRunCommandService.java
 *
 * 用途:异步 agent-run 命令 API 的用例编排层:幂等重放解析、按 operation
 * 分发(起草问题 / 生成规格 / 重生成节点 / 回答 tip),以及目标节点已被回答时
 * 把 ANSWER_TIP 改写为 RESUME_ANSWER。控制器只做 HTTP 与本服务之间的薄翻译层。
 *
 * 在"命令 → 持久化 → Brain → 校验 → checkpoint"链路中,它位于命令入口:
 * 负责入队前的资格预检与幂等指纹计算,把创建出的排队 run 交给 {@link RunService},
 * 实际执行由各 Cycle 服务(DecisionCycleService 等)完成。
 *
 * 编排逻辑属于应用层(由多个 runtime 服务组合而成),不是 HTTP 翻译逻辑。
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
    private final com.specagent.agent.runtime.AgentRunChainReadService chainReadService;
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
                                        com.specagent.agent.runtime.AgentRunChainReadService chainReadService,
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
        // 完整的多选列表属于逻辑请求身份:两个请求若只有第一个选项一致、
        // 其余选择不同,那就是不同的回答——必须在同一个幂等 key 上冲突,
        // 而不是被静默重放。
        //
        // 归一化规则(与 RunService 的执行语义一致:selectedOptionIds 是按用户
        // 顺序的权威选择,selectedOptionId 是遗留的"第一个选择"字段):
        // - selectedOptionIds 存在且非空  → 原样使用(保持用户顺序)。
        // - 否则 selectedOptionId != null → 单选,视为 [selectedOptionId]
        //   (与遗留指纹重载的推导方式相同,因此已落库的单选 run 仍能重放)。
        // - 两者都缺失                    → null(自由文本 / 无选择)。
        // 空的 selectedOptionIds 列表不携带选择语义,归一化为 null。
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
            // 先身份、后守卫——无论可选的 nodeId 是否随请求发送。仅凭 answerId
            // 已能确定目标;若让下面的守卫依赖 nodeId 存在,请求就可能重放已
            // 落库的回答却静默丢弃新提交的内容(本次修复要堵住的丢失路径)。
            Answer persisted =
                    resolveAnswerOperationTarget(projectId, targetRouteId, request);
            if (persisted != null) {
                UUID tipNodeId = routeService.getRoute(targetRouteId)
                        .orElseThrow(() -> ApiException.notFound(
                                "ROUTE_NOT_FOUND", "Route not found"))
                         .tipNodeId();
                // 恢复复用既有 Answer 及其 checkpoint,因此即使回答已是历史,
                // 提交内容也必须与原回答一致。接受新内容等于静默丢弃新内容。
                if (submittedContentDiffers(persisted, effectiveOptionIds, request.freeText())) {
                    throw ApiException.conflict(
                            "ANSWER_CONTENT_MISMATCH",
                            "The node already carries a finalized answer and the submitted"
                                    + " content differs from it; retry the saved answer"
                                    + " (RESUME_ANSWER) or answer the next question");
                }
                // 遗留的/此前入队的回答,可能因后来又起草了新问题而失去 tip 位置。
                // 只要它仍属于所属路线的不可变谱系,就仍可在该路线上恢复。
                // 历史恢复绝不重放其后的 DECISION,也不会移动路线 tip。
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
     * 判断路线 tip 的问题在该路线上是否带有<em>有效</em>(effective)回答——
     * 要是其自身的 Answer,或从它分叉出来的源路线继承的回答
     * (见 {@code route_inherited_answers})。
     *
     * 该前置检查解析回答的方式必须与它所预测的不变式完全一致
     * ({@code GraphInvariantValidator.validateQuestionCanHaveChild} 使用
     * {@link RouteHistoryResolver#resolveEffectiveAnswerRefs})。只查路线本地的
     * 回答会漏掉继承回答:新建分叉路线的 tip 按定义是源路线上已回答的共享节点,
     * 若漏判,起草问题会在入队前就被错误的 {@code UNANSWERED_QUESTION_HAS_CHILD}
     * 拒绝。
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
     * 解析回答操作所指向的已落库回答;身份不一致时直接拒绝,而不是跳过校验。
     *
     * 身份优先级:请求中的 {@code answerId} 是权威——它必须存在于本项目中,
     * 必须与可选的 {@code nodeId} 一致,且必须属于目标路线(可恢复的回答位于
     * 它自己的路线上;恢复时应把该路线作为 {@code sourceRouteId} 传入)。
     * 没有 {@code answerId} 时,使用可选 {@code nodeId} 在目标路线上的本地回答;
     * {@code nodeId} 也为空则回退到目标路线的 tip,与排队 run 将要回答的对象一致。
     *
     * 返回空表示不涉及任何已落库回答,请求走全新提交流程,执行时照常
     * 应用各项资格检查。
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
     * 判断本次提交是否携带与节点已落库回答不同的实际内容。
     *
     * 完全没有内容的提交是纯重试,永远不算"不同";携带内容的提交必须与
     * 已落库回答完全一致(完整选择列表、按用户顺序,外加归一化后的自由文本)
     * 才允许恢复。其余情况视为要修改一条不可变回答,会被拒绝而不是静默丢弃。
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
     * 只要规格上下文实际会用到的回答中,仍有任何一个拖欠 STATE_UPDATE
     * checkpoint,就拒绝生成规格(artifact)。
     *
     * 判定器是共享的 {@link AnswerProcessingGate},作用于该路线的有效回答
     * 历史(路线本地回答 + 继承前缀)——与 {@code ContextBuilder} 折入规格
     * 上下文的是同一集合。此前只查 tip 的路线本地实现会漏掉继承场景:从
     * "STATE_UPDATE 尚未完成"的已回答节点分叉时,该回答会被继承进分支上下文,
     * 而这个门却放行了生成请求。
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
     * DRAFT_QUESTION 会推进路线 tip,因此不允许跨越任何缺少 STATE_UPDATE
     * checkpoint 的有效回答。DecisionCycleService 与图不变式边界会对排队中
     * /竞争中的执行重复同样的判定。
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
