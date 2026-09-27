package com.specagent.workspace.context;

import com.specagent.common.Hashes;
import com.specagent.common.Ids;
import com.specagent.common.Json;
import com.specagent.workspace.graph.NodeRelation;
import com.specagent.workspace.graph.NodeRelationRepository;
import com.specagent.workspace.graph.NodeRelationType;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeRepository;
import com.specagent.workspace.patch.AnswerPatchRepository;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectRepository;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.RouteHistoryResolver;
import com.specagent.workspace.route.RouteRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.Objects;

/**
 * 文件名:ContextBuilder.java
 *
 * 用途:为一次 agent 运行构建确定性的、基于世系(lineage)的上下文快照。
 * 上下文不是全局聊天历史,而是"当前 route 的 tip 沿父指针回放到根节点"的链路,
 * 加上该链路上的回答与补丁。兄弟 route 以及被取代/归档/删除的 route 默认排除,
 * 并记录在 {@code excludedRouteIds} 中。它是 context 包的核心入口,产出的
 * {@link ContextSnapshot} 是交给 Brain 的冻结上下文。
 *
 * 本构建器完全确定性,绝不调用模型或模型网关。
 */
@Service
public class ContextBuilder {

    private final ProjectRepository projectRepository;
    private final RouteRepository routeRepository;
    private final NodeRepository nodeRepository;
    private final AnswerPatchRepository answerPatchRepository;
    private final RouteHistoryResolver routeHistoryResolver;
    private final ContextSnapshotRepository contextSnapshotRepository;
    private final NodeRelationRepository nodeRelationRepository;
    private final Json json;

    public ContextBuilder(ProjectRepository projectRepository,
                         RouteRepository routeRepository,
                         NodeRepository nodeRepository,
                         AnswerPatchRepository answerPatchRepository,
                         ContextSnapshotRepository contextSnapshotRepository,
                         Json json,
                         RouteHistoryResolver routeHistoryResolver,
                         NodeRelationRepository nodeRelationRepository) {
        this.projectRepository = projectRepository;
        this.routeRepository = routeRepository;
        this.nodeRepository = nodeRepository;
        this.answerPatchRepository = answerPatchRepository;
        this.contextSnapshotRepository = contextSnapshotRepository;
        this.json = json;
        this.routeHistoryResolver = routeHistoryResolver;
        this.nodeRelationRepository = nodeRelationRepository;
    }

    /**
     * 允许进入有界一跳语义上下文的关系类型。方向严格保持存储原样;对称类型
     * 已在写入时由 {@code GraphInvariantValidator.endpointsCanonicalized} 做了
     * 规范化。
     */
    private static final java.util.Set<NodeRelationType> SEMANTIC_RELATION_TYPES =
            java.util.Set.of(NodeRelationType.RELATED_TO, NodeRelationType.DEPENDS_ON,
                    NodeRelationType.DERIVED_FROM, NodeRelationType.CONFLICTS_WITH,
                    NodeRelationType.SUPPORTS);

    /**
     * 一条 route 的派生工作材料:挂在世系节点之下(作为溯源父节点)的非交互节点
     * (知识草稿、需求、资源等)。它们永远不会取代可作答的 tip,因此在这里折入
     * route 的上下文——对模型可见、可被引用,但不进入可作答链路。被其他 route
     * 世系认领的节点(兄弟 route 的分支/禁用材料)会被排除。
     */
    private List<UUID> derivedMaterial(UUID projectId, UUID routeId, List<UUID> lineage) {
        Set<UUID> lineageIds = Set.copyOf(lineage);
        Set<UUID> claimedByOtherRoutes = new java.util.HashSet<>();
        for (Route other : routeRepository.findByProject(projectId)) {
            if (other.id().equals(routeId)) {
                continue;
            }
            claimedByOtherRoutes.addAll(resolveLineage(other.tipNodeId()));
        }
        return nodeRepository.findByProject(projectId).stream()
                .filter(node -> !node.isRetracted())
                .filter(node -> node.kind() != com.specagent.workspace.node.NodeKind.INTERACTION)
                .filter(node -> node.parentNodeId() != null
                        && lineageIds.contains(node.parentNodeId()))
                .filter(node -> !lineageIds.contains(node.id()))
                .filter(node -> !claimedByOtherRoutes.contains(node.id()))
                .map(com.specagent.workspace.node.Node::id)
                .toList();
    }

    public ContextSnapshot buildFromActiveRoute(UUID projectId, UUID agentRunId, ContextOperationType operationType) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        if (project.activeRouteId() == null) {
            throw new IllegalStateException("Project has no active route: " + projectId);
        }
        UUID activeRouteId = project.activeRouteId();
        Route activeRoute = routeRepository.findById(activeRouteId)
                .orElseThrow(() -> new IllegalArgumentException("Active route not found: " + activeRouteId));

        if (activeRoute.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
            throw new IllegalStateException(
                    "Active route is not OPEN: " + activeRouteId
                            + " is " + activeRoute.lifecycleStatus().code());
        }

        // 沿父指针从 tip 回放到根,构建世系。一条 route 的上下文就是它的
        // root-to-tip 世系;替换节点属于替换 route 的世系,绝不进入本链路。
        List<UUID> lineage = resolveLineage(activeRoute.tipNodeId());
        List<UUID> includedNodeIds = new ArrayList<>(lineage);
        // 挂在链路之下的派生知识是本 route 的工作材料:知识/资源节点可以附着在
        // 世系节点上而不取代可作答的 tip,因此在这里加入,让模型始终可见、可引用。
        includedNodeIds.addAll(derivedMaterial(projectId, activeRouteId, includedNodeIds));

        List<UUID> includedAnswerIds = routeHistoryResolver
                .resolveEffectiveAnswers(activeRouteId, includedNodeIds)
                .stream().map(a -> a.id()).toList();
        List<UUID> includedPatchIds = answerPatchRepository.findBySourceAnswerIds(includedAnswerIds)
                .stream().map(p -> p.id()).toList();

        List<UUID> excludedRouteIds = routeRepository.findByProject(projectId).stream()
                .map(r -> r.id())
                .filter(id -> !id.equals(activeRouteId))
                .toList();

        Map<String, Object> specialInputsMap = withProjectTitle(project, Map.of());
        String contextHash = computeHash(operationType, includedNodeIds, includedAnswerIds,
                includedPatchIds, excludedRouteIds, List.of(), specialInputsMap);

        ContextSnapshot snapshot = new ContextSnapshot(Ids.random(), projectId, activeRouteId,
                activeRoute.tipNodeId(), operationType, includedNodeIds, includedAnswerIds,
                includedPatchIds, excludedRouteIds, List.of(), List.of(),
                json.write(specialInputsMap), contextHash, Instant.now());

        contextSnapshotRepository.save(snapshot);
        return snapshot;
    }

    /**
     * 为一个显式的运行目标构建绑定 route 的上下文快照。调用方提供入队时冻结到
     * run 上的精确 {@code routeId} 与 {@code inputNodeId};本构建器绝不读取项目
     * 的 active route 指针来挑选目标,因此针对 route A 入队的 run,即使期间活跃
     * 指针已切到 route B,也依然为 route A 构建上下文。
     *
     * 产出的快照始终满足 {@code snapshot.routeId == routeId} 且
     * {@code snapshot.tipNodeId == route.tipNodeId};需要保持入队时 tip 身份的
     * 调用方还会校验 {@code inputNodeId == route.tipNodeId}(此处 fail-closed 强制)。
     */
    public ContextSnapshot buildForRoute(UUID projectId,
                                         UUID routeId,
                                         UUID inputNodeId,
                                         UUID agentRunId,
                                         ContextOperationType operationType) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalArgumentException("Route not found: " + routeId));
        if (!route.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Route " + routeId + " does not belong to project " + projectId);
        }
        if (route.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
            throw new IllegalStateException(
                    "Run target route is not OPEN: " + routeId
                            + " is " + route.lifecycleStatus().code());
        }
        if (!Objects.equals(inputNodeId, route.tipNodeId())) {
            throw new IllegalStateException(
                    "Run input node is no longer the route tip: " + inputNodeId
                            + " (current tip: " + route.tipNodeId() + ")");
        }

        // 沿父指针从 tip 回放到根,构建世系。一条 route 的上下文就是它的
        // root-to-tip 世系;替换节点属于替换 route 的世系,绝不进入本链路。
        List<UUID> lineage = resolveLineage(route.tipNodeId());
        List<UUID> includedNodeIds = new ArrayList<>(lineage);
        // 挂在链路之下的派生知识(见 buildFromActiveRoute)。
        includedNodeIds.addAll(derivedMaterial(projectId, routeId, includedNodeIds));

        List<UUID> includedAnswerIds = routeHistoryResolver
                .resolveEffectiveAnswers(routeId, includedNodeIds)
                .stream().map(a -> a.id()).toList();
        List<UUID> includedPatchIds = answerPatchRepository.findBySourceAnswerIds(includedAnswerIds)
                .stream().map(p -> p.id()).toList();

        List<UUID> excludedRouteIds = routeRepository.findByProject(projectId).stream()
                .map(r -> r.id())
                .filter(id -> !id.equals(routeId))
                .toList();

        Map<String, Object> specialInputsMap = withProjectTitle(project, Map.of());
        String contextHash = computeHash(operationType, includedNodeIds, includedAnswerIds,
                includedPatchIds, excludedRouteIds, List.of(), specialInputsMap);

        ContextSnapshot snapshot = new ContextSnapshot(Ids.random(), projectId, routeId,
                route.tipNodeId(), operationType, includedNodeIds, includedAnswerIds,
                includedPatchIds, excludedRouteIds, List.of(), List.of(),
                json.write(specialInputsMap), contextHash, Instant.now());

        contextSnapshotRepository.save(snapshot);
        return snapshot;
    }

    public ContextSnapshot buildForRegenerate(UUID projectId,
                                              UUID oldRouteId,
                                              UUID targetNodeId,
                                              UUID replacementRouteId,
                                              UUID replacementNodeId,
                                              String userInstruction) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        Node targetNode = nodeRepository.findById(targetNodeId)
                .orElseThrow(() -> new IllegalArgumentException("Target node not found: " + targetNodeId));

        // Regenerate 上下文只携带目标节点的共享父世系:目标节点自身及其回答、
        // 补丁、子树都被有意排除在外。
        List<UUID> parentLineage = resolveLineage(targetNode.parentNodeId());

        List<UUID> includedAnswerIds = routeHistoryResolver
                .resolveEffectiveAnswers(oldRouteId, parentLineage)
                .stream().map(a -> a.id()).toList();
        List<UUID> includedPatchIds = answerPatchRepository.findBySourceAnswerIds(includedAnswerIds)
                .stream().map(p -> p.id()).toList();

        List<UUID> excludedRouteIds = routeRepository.findByProject(projectId).stream()
                .map(r -> r.id())
                .filter(id -> !id.equals(replacementRouteId))
                .toList();

        Map<String, Object> specialInputsMap = withProjectTitle(project, Map.of(
                "oldQuestion", targetNode.question() == null ? "" : targetNode.question(),
                "oldPurpose", targetNode.purpose() == null ? "" : targetNode.purpose(),
                "userInstruction", userInstruction == null ? "" : userInstruction));
        String specialInputs = json.write(specialInputsMap);
        String contextHash = computeHash(ContextOperationType.REGENERATE, parentLineage,
                includedAnswerIds, includedPatchIds, excludedRouteIds, List.of(), specialInputsMap);

        ContextSnapshot snapshot = new ContextSnapshot(Ids.random(), projectId, replacementRouteId,
                replacementNodeId, ContextOperationType.REGENERATE, parentLineage,
                includedAnswerIds, includedPatchIds, excludedRouteIds, List.of(), List.of(),
                specialInputs, contextHash, Instant.now());

        contextSnapshotRepository.save(snapshot);
        return snapshot;
    }

    /**
     * 为模型驱动的问题替换冻结"提案前"上下文。快照锚定在显式的源 route 以及被
     * 否决节点的父 tip 上——此刻尚不存在任何已被接受的替换 route/节点身份。
     */
    public ContextSnapshot buildForReplacement(UUID projectId,
                                               UUID sourceRouteId,
                                               UUID targetNodeId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        Route sourceRoute = routeRepository.findById(sourceRouteId)
                .orElseThrow(() -> new IllegalArgumentException("Source route not found: " + sourceRouteId));
        if (!sourceRoute.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Source route does not belong to project");
        }
        Node targetNode = nodeRepository.findById(targetNodeId)
                .orElseThrow(() -> new IllegalArgumentException("Target node not found: " + targetNodeId));
        if (!targetNode.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Target node does not belong to project");
        }

        List<UUID> sourceLineage = resolveLineage(sourceRoute.tipNodeId());
        if (!sourceLineage.contains(targetNodeId)) {
            throw new IllegalArgumentException("Target node is not on the explicit source route");
        }
        List<UUID> parentLineage = resolveLineage(targetNode.parentNodeId());
        List<UUID> includedAnswerIds = routeHistoryResolver
                .resolveEffectiveAnswers(sourceRouteId, parentLineage)
                .stream().map(answer -> answer.id()).toList();
        List<UUID> includedPatchIds = answerPatchRepository.findBySourceAnswerIds(includedAnswerIds)
                .stream().map(patch -> patch.id()).toList();

        List<UUID> excludedRouteIds = routeRepository.findByProject(projectId).stream()
                .map(Route::id)
                .filter(id -> !id.equals(sourceRouteId))
                .toList();
        Map<String, Object> specialInputsMap = withProjectTitle(project, Map.of(
                "oldQuestion", targetNode.question() == null ? "" : targetNode.question(),
                "oldPurpose", targetNode.purpose() == null ? "" : targetNode.purpose()));
        String specialInputs = json.write(specialInputsMap);
        String contextHash = computeHash(ContextOperationType.REGENERATE, parentLineage,
                includedAnswerIds, includedPatchIds, excludedRouteIds, List.of(), specialInputsMap);

        ContextSnapshot snapshot = new ContextSnapshot(
                Ids.random(), projectId, sourceRouteId, targetNode.parentNodeId(),
                ContextOperationType.REGENERATE, parentLineage, includedAnswerIds,
                includedPatchIds, excludedRouteIds, List.of(), List.of(),
                specialInputs, contextHash, Instant.now());
        contextSnapshotRepository.save(snapshot);
        return snapshot;
    }

    /**
     * 以任意节点为锚点,为"就该节点问问 AI"这类上下文查询构建读取上下文。
     *
     * 世系即锚点自身沿父指针回放到根的链路;读取上下文是调用方显式指定的
     * {@code routeId}(绝不回退到 active/first/latest)。用户的问题被冻结进
     * {@code specialInputs},因此也参与上下文哈希。
     */
    public ContextSnapshot buildForNodeQuery(UUID projectId,
                                             UUID routeId,
                                             UUID anchorNodeId,
                                             String userQuestion) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        // 对节点查询而言 route 是可选的读取上下文。游离节点不属于任何 route
        // (routeIds=[]),其查询上下文就是锚点自身。routeId 绝不能成为硬性的
        // 准入门槛。
        Route route = null;
        if (routeId != null) {
            route = routeRepository.findById(routeId)
                    .orElseThrow(() -> new IllegalArgumentException("Route not found: " + routeId));
            if (!route.projectId().equals(projectId)) {
                throw new IllegalArgumentException("Route does not belong to project: " + routeId);
            }
        }
        Node anchor = nodeRepository.findById(anchorNodeId)
                .orElseThrow(() -> new IllegalArgumentException("Anchor node not found: " + anchorNodeId));
        if (!anchor.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Anchor node does not belong to project: " + anchorNodeId);
        }

        // B4.3 — 锚点准入检查,fail-closed:
        // - 已回撤的锚点永远不能成为查询对象;
        // - 绑定 route 的查询要求锚点位于该 route 的规范化世系上
        //   (共享/跨 route 的锚点会被拒绝);
        // - 无 route(routeId == null)的查询要求锚点必须是真正的游离节点:
        //   若它实际属于某个 route,就不能被悄悄当作无 route 处理。
        if (anchor.isRetracted()) {
            throw new IllegalArgumentException("Anchor node is retracted: " + anchorNodeId);
        }
        if (routeId != null) {
            if (!resolveLineage(route.tipNodeId()).contains(anchorNodeId)) {
                throw new IllegalArgumentException(
                        "Anchor node is not on the explicit route lineage: " + anchorNodeId);
            }
        } else if (isAnchorMemberOfAnyRoute(projectId, anchorNodeId)) {
            throw new IllegalArgumentException(
                    "Anchor node belongs to a route and requires an explicit routeId: " + anchorNodeId);
        }

        List<UUID> lineage = resolveLineage(anchorNodeId);
        List<UUID> includedAnswerIds = routeId == null
                ? List.of()
                : routeHistoryResolver
                        .resolveEffectiveAnswers(routeId, lineage)
                        .stream().map(answer -> answer.id()).toList();
        List<UUID> includedPatchIds = answerPatchRepository.findBySourceAnswerIds(includedAnswerIds)
                .stream().map(patch -> patch.id()).toList();
        // 没有显式 route 就没有可读取的 route:排除全部 route,使上下文只含
        // 锚点自身,而不是整个工作区。
        List<UUID> excludedRouteIds = routeId == null
                ? routeRepository.findByProject(projectId).stream()
                        .map(Route::id).toList()
                : routeRepository.findByProject(projectId).stream()
                        .map(Route::id)
                        .filter(id -> !id.equals(routeId))
                        .toList();

        // B7 — 有界一跳语义上下文。只有触及锚点、且类型在配置范围内的 ACTIVE
        // 关系才进入上下文;相关联的规范化节点 id 单独记录,绝不污染世系。
        // 不做递归:关联节点的邻居被排除。
        List<ContextRelation> relations = resolveSemanticRelations(projectId, anchorNodeId);
        List<UUID> relatedNodeIds = relations.stream()
                .map(r -> r.sourceNodeId().equals(anchorNodeId) ? r.targetNodeId() : r.sourceNodeId())
                .distinct()
                .toList();

        Map<String, Object> specialInputsMap = withProjectTitle(project, Map.of(
                "userQuestion", userQuestion == null ? "" : userQuestion));
        String specialInputs = json.write(specialInputsMap);
        String contextHash = computeHash(ContextOperationType.NODE_QUERY, lineage,
                includedAnswerIds, includedPatchIds, excludedRouteIds, relations, specialInputsMap);

        ContextSnapshot snapshot = new ContextSnapshot(
                Ids.random(), projectId, routeId, anchorNodeId,
                ContextOperationType.NODE_QUERY, lineage, includedAnswerIds,
                includedPatchIds, excludedRouteIds, relatedNodeIds, relations,
                specialInputs, contextHash, Instant.now());
        contextSnapshotRepository.save(snapshot);
        return snapshot;
    }

    /**
     * B4.3 辅助方法:判断锚点节点是否位于项目内任意 route 的规范化世系上。
     * 范围限定在项目的 route 列表内(绝不全工作区扫描);用于拒绝那种锚点实际
     * 属于某条 route 的"无 route 查询"。
     */
    private boolean isAnchorMemberOfAnyRoute(UUID projectId, UUID anchorNodeId) {
        for (Route candidate : routeRepository.findByProject(projectId)) {
            if (resolveLineage(candidate.tipNodeId()).contains(anchorNodeId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * B7 辅助方法:获取锚点的有界一跳语义上下文。返回触及锚点、类型在配置范围内
     * 的 ACTIVE 关系,保持规范化的存储方向,不做递归。
     */
    private List<ContextRelation> resolveSemanticRelations(UUID projectId, UUID anchorNodeId) {
        List<ContextRelation> result = new ArrayList<>();
        for (NodeRelation relation : nodeRelationRepository.findActiveTouchingNode(projectId, anchorNodeId)) {
            if (!SEMANTIC_RELATION_TYPES.contains(relation.relationType())) {
                continue;
            }
            result.add(new ContextRelation(
                    relation.sourceNodeId(), relation.targetNodeId(), relation.relationType().code()));
        }
        return List.copyOf(result);
    }

    /**
     * 沿 {@code parentNodeId} 指针从 tip 向上回溯,解析一条 route 的
     * root-to-tip 世系。链路是确定的:一条 route 的上下文恰好就是这条链,
     * 兄弟节点或替换节点绝不会出现在其中。
     */
    private List<UUID> resolveLineage(UUID tipNodeId) {
        return routeHistoryResolver.resolveLineage(tipNodeId);
    }

    private String computeHash(ContextOperationType operationType,
                               List<UUID> nodeIds,
                               List<UUID> answerIds,
                               List<UUID> patchIds,
                               List<UUID> excludedRouteIds,
                               List<ContextRelation> relations,
                               Map<String, Object> specialInputs) {
        List<UUID> sortedNodes = new ArrayList<>(nodeIds);
        List<UUID> sortedAnswers = new ArrayList<>(answerIds);
        List<UUID> sortedPatches = new ArrayList<>(patchIds);
        List<UUID> sortedExcluded = new ArrayList<>(excludedRouteIds);
        sortedNodes.sort(Comparator.naturalOrder());
        sortedAnswers.sort(Comparator.naturalOrder());
        sortedPatches.sort(Comparator.naturalOrder());
        sortedExcluded.sort(Comparator.naturalOrder());
        List<ContextRelation> sortedRelations = new ArrayList<>(relations == null ? List.of() : relations);
        sortedRelations.sort(Comparator.comparing(ContextRelation::sourceNodeId)
                .thenComparing(ContextRelation::targetNodeId)
                .thenComparing(ContextRelation::relationType));
        Map<String, Object> sortedSpecialInputs = specialInputs == null
                ? Map.of()
                : new TreeMap<>(specialInputs);
        String canonical = operationType.code()
                + "|N:" + sortedNodes
                + "|A:" + sortedAnswers
                + "|P:" + sortedPatches
                + "|X:" + sortedExcluded
                + "|R:" + sortedRelations
                + "|S:" + sortedSpecialInputs;
        return Hashes.sha256Hex(canonical);
    }

    /**
     * 把项目元数据冻结进各操作特有的 special inputs。借助这个辅助方法,替换/
     * 重建的输入可以继续扩展,同时保证项目标题始终是快照及其哈希的一部分。
     */
    private Map<String, Object> withProjectTitle(Project project,
                                                  Map<String, Object> existing) {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("projectTitle", project.title() == null ? "" : project.title());
        if (existing != null) {
            merged.putAll(existing);
        }
        return Map.copyOf(merged);
    }
}
