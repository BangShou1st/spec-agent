package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.protocol.AgentEvent;
import com.specagent.agent.runtime.AgentRunRepository;
import com.specagent.agent.runtime.AgentRunRequestFingerprint;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunTriggerType;
import com.specagent.agent.runtime.LoopLinkage;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeRepository;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectRepository;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.RouteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:RunService.java
 *
 * 用途:AgentRun 的创建、认领与读取入口。在"命令 → 持久化 → Brain →
 * 校验 → checkpoint"链路中,它处于最前端的"命令入队"环节:把各种客户端
 * 命令(起草问题、提交回答、生成 spec、重新生成节点、节点查询、自治续跑)
 * 落库为排队 run 并写入 RUN_CREATED 事件,供 {@link RunWorker} 轮询认领。
 * 目标 route 的解析与 fail-closed 校验也集中在这里。
 *
 * 入队路径在事务内先锁定项目行(lockProjectForEnqueue),与项目删除的
 * 检查窗口互斥:REQUIRES 传播使其自然并入外层命令事务,独立调用时
 * 则由本注解开启新事务,保证 FOR UPDATE 锁一直持有到提交。
 */
@Service
@Transactional
public class RunService {

    private final AgentRunService agentRunService;
    private final AgentRunRepository agentRunRepository;
    private final ProjectRepository projectRepository;
    private final RouteRepository routeRepository;
    private final AgentRunEventService eventService;
    private final NodeRepository nodeRepository;
    private final ExecutionFence executionFence;

    public RunService(AgentRunService agentRunService,
                      AgentRunRepository agentRunRepository,
                      ProjectRepository projectRepository,
                      RouteRepository routeRepository,
                      AgentRunEventService eventService,
                      NodeRepository nodeRepository,
                      ExecutionFence executionFence) {
        this.agentRunService = agentRunService;
        this.agentRunRepository = agentRunRepository;
        this.projectRepository = projectRepository;
        this.routeRepository = routeRepository;
        this.eventService = eventService;
        this.nodeRepository = nodeRepository;
        this.executionFence = executionFence;
    }

    public AgentRun createQueuedDraftQuestion(UUID projectId) {
        return createQueuedDraftQuestion(projectId, null);
    }

    public AgentRun createQueuedDraftQuestion(UUID projectId, String idempotencyKey) {
        String fingerprint = AgentRunRequestFingerprint.forClientRequest(
                projectId, "DRAFT_QUESTION", null, null, null, null, null);
        return createQueuedDraftQuestion(projectId, idempotencyKey, fingerprint);
    }

    public AgentRun createQueuedDraftQuestion(UUID projectId,
                                              String idempotencyKey,
                                              String requestFingerprint) {
        return createQueuedDraftQuestion(projectId, idempotencyKey, requestFingerprint, null);
    }

    /**
     * 在 EXPLICIT route 上排队一次问题起草({@code explicitRouteId} 为 null
     * 时落在 Active route 上)。
     *
     * explicit 模式正是多条 route 能独立起草的原因:run 在整个生命周期
     * 内拥有自己的 route,而不是反复读取项目的唯一 Active 指针。route 选择
     * 会记入 run payload,worker 执行时可以据此重建同样的决策,无需猜测。
     */
    public AgentRun createQueuedDraftQuestion(UUID projectId,
                                              String idempotencyKey,
                                              String requestFingerprint,
                                              UUID explicitRouteId) {
        // 先取项目行锁:与项目删除在同一把锁上串行化,删除事务提交后
        // 不可能再为本项目插入新 run(反之,入队持有锁期间删除会等待并在
        // 检查时看到非终态 run 而失败)。
        lockProjectForEnqueue(projectId);
        Route route = resolveTargetRoute(projectId, explicitRouteId);

        var created = agentRunService.createWithIdempotency(
                projectId, route.id(), AgentRunTriggerType.DECISION_CYCLE,
                route.tipNodeId(), null, "DRAFT_QUESTION", idempotencyKey, requestFingerprint);
        AgentRun run = created.run();
        appendRunCreatedIfInserted(created, Map.of(
                "triggerType", AgentRunTriggerType.DECISION_CYCLE.code(),
                "operation", "DRAFT_QUESTION",
                "routeId", route.id().toString(),
                "routeSelection", routeSelection(explicitRouteId)));
        return run;
    }

    /**
     * 解析新 run 的目标 route。
     *
     * {@code explicitRouteId == null} 完全保持原有语义:取项目的
     * Active route,没有则 fail-closed。显式 route 必须属于本项目且仍为
     * OPEN——run 绝不能写入已归档/已被取代的链。
     */
    private Route resolveTargetRoute(UUID projectId, UUID explicitRouteId) {
        if (explicitRouteId == null) {
            Project project = projectRepository.findById(projectId)
                    .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
            if (project.activeRouteId() == null) {
                throw new RouteTargetConflictException(
                        RouteTargetConflictException.Reason.NO_ACTIVE_ROUTE,
                        "Project has no active route: " + projectId);
            }
            return routeRepository.findById(project.activeRouteId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Active route not found: " + project.activeRouteId()));
        }
        Route route = routeRepository.findById(explicitRouteId)
                .orElseThrow(() -> new IllegalArgumentException("Route not found: " + explicitRouteId));
        if (!route.projectId().equals(projectId)) {
            throw new IllegalArgumentException(
                    "Route " + explicitRouteId + " does not belong to project " + projectId);
        }
        if (route.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
            throw new RouteTargetConflictException(
                    RouteTargetConflictException.Reason.ROUTE_NOT_OPEN,
                    "Route is not open: " + explicitRouteId + " is " + route.lifecycleStatus().code());
        }
        return route;
    }

    /** 写入 payload 的标记,worker 用它重建 route 决策。 */
    static String routeSelection(UUID explicitRouteId) {
        return explicitRouteId == null ? "ACTIVE" : "EXPLICIT";
    }

    public UUID createQueuedRunWithInput(UUID projectId,
                                         String operation,
                                         UUID nodeId,
                                         UUID selectedOptionId,
                                         String freeText,
                                         UUID answerId) {
        return createQueuedRunWithInputResult(projectId, operation, nodeId, selectedOptionId,
                freeText, answerId, null).id();
    }

    public UUID createQueuedRunWithInput(UUID projectId,
                                         String operation,
                                         UUID nodeId,
                                         UUID selectedOptionId,
                                         String freeText,
                                         UUID answerId,
                                         AgentEvent.PersistenceIntent persistenceIntent) {
        return createQueuedRunWithInputResult(projectId, operation, nodeId, selectedOptionId,
                freeText, answerId, null, persistenceIntent).id();
    }

    public UUID createQueuedRunWithInput(UUID projectId,
                                         String operation,
                                         UUID nodeId,
                                         UUID selectedOptionId,
                                         String freeText,
                                         UUID answerId,
                                         String idempotencyKey) {
        return createQueuedRunWithInputResult(projectId, operation, nodeId, selectedOptionId,
                freeText, answerId, idempotencyKey).id();
    }

    public AgentRun createQueuedRunWithInputResult(UUID projectId,
                                                   String operation,
                                                   UUID nodeId,
                                                   UUID selectedOptionId,
                                         String freeText,
                                         UUID answerId,
                                         String idempotencyKey) {
        return createQueuedRunWithInputResult(projectId, operation, nodeId, selectedOptionId,
                freeText, answerId, idempotencyKey, null, null);
    }

    public AgentRun createQueuedRunWithInputResult(UUID projectId,
                                                   String operation,
                                                   UUID nodeId,
                                                   UUID selectedOptionId,
                                                   String freeText,
                                                   UUID answerId,
                                                   String idempotencyKey,
                                                   AgentEvent.PersistenceIntent persistenceIntent) {
        return createQueuedRunWithInputResult(projectId, operation, nodeId, selectedOptionId,
                freeText, answerId, idempotencyKey, null, persistenceIntent);
    }

    public AgentRun createQueuedRunWithInputResult(UUID projectId,
                                                   String operation,
                                                   UUID nodeId,
                                                   UUID selectedOptionId,
                                                   String freeText,
                                                   UUID answerId,
                                                   String idempotencyKey,
                                                   String requestFingerprint) {
        return createQueuedRunWithInputResult(projectId, operation, nodeId, selectedOptionId,
                freeText, answerId, idempotencyKey, requestFingerprint, null);
    }

    public AgentRun createQueuedRunWithInputResult(UUID projectId,
                                                   String operation,
                                                   UUID nodeId,
                                                   UUID selectedOptionId,
                                                   String freeText,
                                                   UUID answerId,
                                                   String idempotencyKey,
                                                   String requestFingerprint,
                                                   AgentEvent.PersistenceIntent persistenceIntent) {
        return createQueuedRunWithInputResultForRoute(projectId, operation, nodeId, selectedOptionId,
                freeText, answerId, idempotencyKey, requestFingerprint, persistenceIntent, null);
    }

    /**
     * 与 {@link #createQueuedRunWithInputResult} 相同,但指定 EXPLICIT 目标
     * route(传 null 则 Active-route 行为完全不变)。
     *
     * 因此多条 route 可以各自独立回答:run 携带自己的 route id,执行路径
     * 从 run 本身解析 route,而不是重读项目的唯一 Active 指针。
     */
    public AgentRun createQueuedRunWithInputResultForRoute(UUID projectId,
                                                           String operation,
                                                           UUID nodeId,
                                                           UUID selectedOptionId,
                                                           String freeText,
                                                           UUID answerId,
                                                           String idempotencyKey,
                                                           String requestFingerprint,
                                                           AgentEvent.PersistenceIntent persistenceIntent,
                                                           UUID explicitRouteId) {
        return createQueuedRunWithInputResultForRoute(projectId, operation, nodeId, selectedOptionId,
                null, freeText, answerId, idempotencyKey, requestFingerprint, persistenceIntent,
                explicitRouteId);
    }

    /**
     * 与上一方法相同,但携带完整的多选选项列表。{@code selectedOptionIds}
     * 是权威选择(用户顺序);{@code selectedOptionId} 保留为旧的
     * "首个选择"字段。两者均可为 null。
     */
    public AgentRun createQueuedRunWithInputResultForRoute(UUID projectId,
                                                           String operation,
                                                           UUID nodeId,
                                                           UUID selectedOptionId,
                                                           List<UUID> selectedOptionIds,
                                                           String freeText,
                                                           UUID answerId,
                                                           String idempotencyKey,
                                                           String requestFingerprint,
                                                           AgentEvent.PersistenceIntent persistenceIntent,
                                                           UUID explicitRouteId) {
        String fingerprint = requestFingerprint != null ? requestFingerprint
                : (selectedOptionIds != null
                        ? AgentRunRequestFingerprint.forClientRequest(
                        projectId, operation, nodeId, explicitRouteId, answerId, selectedOptionId,
                        selectedOptionIds, freeText, persistenceIntent)
                        : AgentRunRequestFingerprint.forClientRequest(
                        projectId, operation, nodeId, explicitRouteId, answerId, selectedOptionId, freeText,
                        persistenceIntent));
        lockProjectForEnqueue(projectId);
        Route route = resolveTargetRoute(projectId, explicitRouteId);

        UUID inputNodeId = nodeId != null ? nodeId : route.tipNodeId();
        var created = agentRunService.createWithIdempotency(
                projectId, route.id(), AgentRunTriggerType.ANSWER_CYCLE,
                inputNodeId, null, operation, idempotencyKey, fingerprint);
        AgentRun run = created.run();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("triggerType", AgentRunTriggerType.ANSWER_CYCLE.code());
        payload.put("operation", operation != null ? operation : "");
        payload.put("routeId", route.id().toString());
        payload.put("routeSelection", routeSelection(explicitRouteId));
        if (selectedOptionId != null) payload.put("selectedOptionId", selectedOptionId.toString());
        if (selectedOptionIds != null && !selectedOptionIds.isEmpty()) {
            payload.put("selectedOptionIds", selectedOptionIds.stream().map(UUID::toString).toList());
        }
        if (freeText != null) payload.put("freeText", freeText);
        if (answerId != null) payload.put("answerId", answerId.toString());
        if (persistenceIntent != null) payload.put("persistenceIntent", persistenceIntent.name());
        appendRunCreatedIfInserted(created, payload);
        return run;
    }

    public UUID createQueuedNodeQuery(UUID projectId, UUID routeId, UUID nodeId, String question) {
        return createQueuedNodeQuery(projectId, routeId, nodeId, question, null, null).id();
    }

    /**
     * 带幂等身份的节点查询入队:失败恢复重试用确定性键
     * {@code retry:<failedRunId>} 保证双击/并发只产生一个有效尝试。
     */
    public AgentRun createQueuedNodeQuery(UUID projectId, UUID routeId, UUID nodeId,
                                          String question, String idempotencyKey,
                                          String requestFingerprint) {
        lockProjectForEnqueue(projectId);
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        // route 是可选的读取上下文:游离节点(routeIds=[])查询时
        // routeId 为 null,仅以锚点节点作为上下文。
        if (routeId != null) {
            Route route = routeRepository.findById(routeId)
                    .orElseThrow(() -> new IllegalArgumentException("Route not found: " + routeId));
            if (!route.projectId().equals(projectId)) {
                throw new IllegalArgumentException("Route does not belong to project: " + routeId);
            }
        }
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Node query question must not be blank");
        }

        String fingerprint = requestFingerprint != null ? requestFingerprint
                : AgentRunRequestFingerprint.forClientRequest(
                        projectId, "NODE_QUERY", nodeId, routeId, null, null, question);
        var created = agentRunService.createWithIdempotency(
                projectId, routeId, AgentRunTriggerType.NODE_QUERY, nodeId, null,
                "NODE_QUERY", idempotencyKey, fingerprint);
        AgentRun run = created.run();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("triggerType", AgentRunTriggerType.NODE_QUERY.code());
        payload.put("operation", "NODE_QUERY");
        payload.put("routeId", routeId == null ? null : routeId.toString());
        payload.put("nodeId", nodeId.toString());
        payload.put("question", question);
        appendRunCreatedIfInserted(created, payload);
        return run;
    }

    public AgentRun createQueuedArtifactGeneration(UUID projectId) {
        return createQueuedArtifactGeneration(projectId, null);
    }

    public AgentRun createQueuedArtifactGeneration(UUID projectId, String idempotencyKey) {
        String fingerprint = AgentRunRequestFingerprint.forClientRequest(
                projectId, "GENERATE_ARTIFACT", null, null, null, null, null);
        return createQueuedArtifactGeneration(projectId, idempotencyKey, fingerprint);
    }

    public AgentRun createQueuedArtifactGeneration(UUID projectId,
                                                   String idempotencyKey,
                                                   String requestFingerprint) {
        return createQueuedArtifactGeneration(projectId, idempotencyKey, requestFingerprint, null);
    }

    /** 在 EXPLICIT route 上生成 artifact(传 null 保持 Active route)。 */
    public AgentRun createQueuedArtifactGeneration(UUID projectId,
                                                   String idempotencyKey,
                                                   String requestFingerprint,
                                                   UUID explicitRouteId) {
        lockProjectForEnqueue(projectId);
        Route route = resolveTargetRoute(projectId, explicitRouteId);

        var created = agentRunService.createWithIdempotency(
                projectId, route.id(), AgentRunTriggerType.GENERATE_SPEC,
                route.tipNodeId(), null, "GENERATE_ARTIFACT", idempotencyKey, requestFingerprint);
        AgentRun run = created.run();
        appendRunCreatedIfInserted(created, Map.of(
                "triggerType", AgentRunTriggerType.GENERATE_SPEC.code(),
                "operation", "GENERATE_ARTIFACT",
                "routeId", route.id().toString(),
                "routeSelection", routeSelection(explicitRouteId)));
        return run;
    }

    public AgentRun createQueuedRegenerate(UUID projectId, UUID sourceRouteId,
                                           UUID targetNodeId, String instruction) {
        return createQueuedRegenerate(projectId, sourceRouteId, targetNodeId, instruction, null);
    }

    public AgentRun createQueuedRegenerate(UUID projectId, UUID sourceRouteId,
                                           UUID targetNodeId, String instruction,
                                           String idempotencyKey) {
        String normalizedInstruction = instruction != null && !instruction.isBlank()
                ? instruction : null;
        String fingerprint = AgentRunRequestFingerprint.forClientRequest(
                projectId, "REGENERATE_NODE", targetNodeId, sourceRouteId,
                null, null, normalizedInstruction);
        return createQueuedRegenerate(projectId, sourceRouteId, targetNodeId,
                instruction, idempotencyKey, fingerprint);
    }

    public AgentRun createQueuedRegenerate(UUID projectId, UUID sourceRouteId,
                                           UUID targetNodeId, String instruction,
                                           String idempotencyKey,
                                           String requestFingerprint) {
        Route route = routeRepository.findById(sourceRouteId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Route not found: " + sourceRouteId));
        if (!route.projectId().equals(projectId)) {
            throw new IllegalArgumentException(
                    "Route does not belong to project: " + sourceRouteId);
        }
        lockProjectForEnqueue(projectId);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("triggerType", AgentRunTriggerType.REGENERATE_NODE.code());
        payload.put("operation", "REGENERATE_NODE");
        payload.put("routeId", sourceRouteId.toString());
        payload.put("nodeId", targetNodeId.toString());
        if (instruction != null && !instruction.isBlank()) payload.put("freeText", instruction);

        var created = agentRunService.createWithIdempotency(
                projectId, sourceRouteId, AgentRunTriggerType.REGENERATE_NODE,
                targetNodeId, null, "REGENERATE_NODE", idempotencyKey, requestFingerprint);
        AgentRun run = created.run();
        appendRunCreatedIfInserted(created, payload);
        return run;
    }

    private void appendRunCreatedIfInserted(AgentRunService.CreateResult created,
                                            Map<String, Object> payload) {
        if (created.inserted()) {
            eventService.append(created.run().id(), AgentRunPhase.CREATED,
                    "RUN_CREATED", payload);
        }
    }

    /**
     * 为终态父 run 创建自治续跑子 run。
     *
     * 链路规则:链根(没有持久化 root/cycle)派生的子 run 记
     * {@code rootRunId = parent.id}、cycle 为 1;更深的父 run 沿用自己的
     * root 并把 cycle 加一。stale 锚点复用现有的 {@code inputNodeId} 机制
     * (不加新列):子 run 记录从父行推导出的期望 tip,Slice 3 执行时若活跃
     * tip 不再等于它就 fail-closed(与 {@code DecisionCycleService} 的起草
     * 目标同一检查,对空 route 是 null 安全的)。不引入任何新的锚点元数据。
     *
     * stale 锚点闸门:期望 tip 仅从父行推导——父 run 推进了 tip 就用其
     * 产出节点,否则用它决策时的输入节点(空 route 上两者皆 null)。活跃
     * tip 必须仍等于它,否则创建抛出 {@link StaleRunTargetException},
     * 而不是让自治续跑去追随更新的外部因果。该规则只读 Runtime 图状态与
     * 产出引用;任何 action family 都不参与。
     *
     * 恰好一次:确定性 key {@code "continue:<parentRunId>"} 加项目作用域
     * 的幂等唯一索引仲裁并发创建者——重复调用返回已持久化的那个子 run,
     * 绝不产生第二行。方法只接收父 run 行:任何 action family、冲突或其他
     * 语义输入都不参与子 run 身份。
     */
    public AgentRunService.CreateResult createContinueRun(AgentRun parent) {
        if (parent.routeId() == null) {
            throw new StaleRunTargetException(
                    "Parent run " + parent.id()
                            + " has no route to anchor a continuation; refusing");
        }
        Route route = routeRepository.findById(parent.routeId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Parent route not found: " + parent.routeId()));
        if (!route.projectId().equals(parent.projectId())) {
            throw new IllegalArgumentException(
                    "Parent route does not belong to project: " + parent.routeId());
        }
        UUID expectedTip = parent.producedNodeId() != null
                ? parent.producedNodeId() : parent.inputNodeId();
        // Tip 语义:产出的知识/资源节点挂在活跃 tip 之下以保留出处,
        // 但不取代可回答的问题 tip。链路仍可继续——它锚定在活跃 tip,
        // 检查只在 tip 移动到父效果无法挂靠的位置(真正的外部图移动)
        // 时才拒绝。
        if (!Objects.equals(route.tipNodeId(), expectedTip)
                && !tipIsAncestorOf(route.tipNodeId(), expectedTip)) {
            throw new StaleRunTargetException(
                    "Parent route tip moved since parent " + parent.id()
                            + " completed: expected " + expectedTip
                            + " but live tip is " + route.tipNodeId()
                            + "; refusing autonomous continuation");
        }
        UUID rootId = parent.rootRunId() != null ? parent.rootRunId() : parent.id();
        int parentCycle = parent.cycleIndex() != null ? parent.cycleIndex() : 0;
        int childCycle = parentCycle + 1;
        String key = "continue:" + parent.id();
        String fingerprint = AgentRunRequestFingerprint.forContinuation(
                parent.projectId(), parent.id(), childCycle);

        lockProjectForEnqueue(parent.projectId());
        var created = agentRunService.createWithIdempotency(
                parent.projectId(), route.id(), AgentRunTriggerType.CONTINUE_CYCLE,
                // 子 run 锚定在活跃 tip:严格场景下等于 expectedTip;
                // 父 run 的产出节点只是挂靠在其下时,就是那个待回答的问题。
                route.tipNodeId(), null, "CONTINUE", key, fingerprint,
                new LoopLinkage(parent.id(), rootId, childCycle));
        AgentRun run = created.run();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("triggerType", AgentRunTriggerType.CONTINUE_CYCLE.code());
        payload.put("operation", "CONTINUE");
        payload.put("routeId", route.id().toString());
        // 续跑子 run 生活在父 run 的 route 上。当该 route 不是项目的
        // Active route 时,子 run 按构造就是一个 EXPLICIT-route run——
        // 在这里记录下来,可以让 context guard 不会误拒独立运行链路的
        // 子 run。(stale 指针风险已由上面的期望 tip 检查覆盖。)
        payload.put("routeSelection", routeSelection(
                route.id().equals(activeRouteIdOrNull(parent.projectId())) ? null : route.id()));
        payload.put("parentRunId", parent.id().toString());
        payload.put("rootRunId", rootId.toString());
        payload.put("cycleIndex", childCycle);
        appendRunCreatedIfInserted(created, payload);
        return created;
    }

    /**
     * 当 {@code tip} 位于 {@code expected} 的父链上时为真——即父 run 的
     * 产出节点挂在活跃 tip 之下而没有取代它(派生知识、附加资源)。
     * 节点行缺失或链路断裂一律按"否"处理,绝不猜测。
     */
    private boolean tipIsAncestorOf(UUID tip, UUID expected) {
        if (tip == null || expected == null) {
            return false;
        }
        UUID current = expected;
        while (current != null) {
            Node node = nodeRepository.findById(current).orElse(null);
            if (node == null) {
                return false;
            }
            current = node.parentNodeId();
            if (tip.equals(current)) {
                return true;
            }
        }
        return false;
    }

    /** 项目的 Active route id;项目没有 Active route 时返回 null。 */
    public UUID activeRouteIdOrNull(UUID projectId) {
        return projectRepository.findById(projectId)
                .map(Project::activeRouteId)
                .orElse(null);
    }

    /**
     * 入队前的项目行锁(FOR KEY SHARE)。锁只在本事务提交/回滚前保持,与
     * {@code ProjectDeletionService} 的"FOR UPDATE 锁项目行 → 检查非终态
     * run → 删除"互斥串行化:删除提交后本项目不可能再出现新 run;入队持有
     * 锁期间删除会被推迟到其后并在检查中看到刚创建的 run,以 409 拒绝。
     * 刻意用 KEY SHARE 而非 FOR UPDATE:入队之间(包括同线程嵌套的
     * REQUIRES_NEW 验收事务再次入队)不需要互斥,写锁会造成自死锁。
     */
    private void lockProjectForEnqueue(UUID projectId) {
        projectRepository.lockByIdForKeyShare(projectId);
    }

    public UUID getActiveRouteId(UUID projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.activeRouteId() == null) {
            throw new IllegalStateException("Project has no active route: " + projectId);
        }
        return project.activeRouteId();
    }

    /*
     * 认领携带所有权代次(fencing token):认领语句在领取的同时验证全局
     * 代次并把代次落到 run 行——丢锁执行器的认领原子落空(不返回任务),
     * 新执行器接管后旧执行器绝不能认领新任务。
     *
     * 第四轮所有权协议:认领在 RunService 事务(@Transactional)内先对
     * executor_ownership 行取 FOR SHARE 并验证代次,再执行认领 UPDATE——
     * 接管的代次递增与该锁互斥,认领语句的快照子查询不可能在"锁验证之后、
     * 提交之前"读到过期代次通过条件(R4-A 的语句快照窗口闭合)。已闩锁
     * 丢失的执行器在取锁处即被拒绝(R4-C)。
     */
    private long fencedClaimEpoch() {
        return executionFence.lockOwnershipForWrite();
    }

    public Optional<AgentRun> claimNext() { return agentRunRepository.claimNextDecisionCycleRun(fencedClaimEpoch()); }
    public Optional<AgentRun> claimNextArtifact() { return agentRunRepository.claimNextArtifactRun(fencedClaimEpoch()); }
    public Optional<AgentRun> claimArtifactRun(UUID runId) { return agentRunRepository.claimArtifactRun(runId, fencedClaimEpoch()); }
    public Optional<AgentRun> claimNextRegenerate() { return agentRunRepository.claimNextRegenerateRun(fencedClaimEpoch()); }
    public Optional<AgentRun> claimDecisionCycleRun(UUID runId) { return agentRunRepository.claimDecisionCycleRun(runId, fencedClaimEpoch()); }
    public Optional<AgentRun> claimNextAnswerCycle() { return agentRunRepository.claimNextAnswerCycleRun(fencedClaimEpoch()); }
    /** 按 id 认领一条指定的排队 answer-cycle run(共享队列安全)。 */
    public Optional<AgentRun> claimAnswerCycleRun(UUID runId) { return agentRunRepository.claimAnswerCycleRun(runId, fencedClaimEpoch()); }
    public Optional<AgentRun> claimNextNodeQuery() { return agentRunRepository.claimNextNodeQueryRun(fencedClaimEpoch()); }
    public Optional<AgentRun> claimNodeQueryRun(UUID runId) { return agentRunRepository.claimNodeQueryRun(runId, fencedClaimEpoch()); }
    public Optional<AgentRun> claimNextContinue() { return agentRunRepository.claimNextContinueRun(fencedClaimEpoch()); }
    public Optional<AgentRun> getRun(UUID runId) { return agentRunService.getRun(runId); }
}
