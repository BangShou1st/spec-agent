package com.specagent.workspace.graph;

import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.graph.NodeRelationRepository;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteHistoryResolver;
import com.specagent.workspace.route.RouteService;
import com.specagent.workspace.route.ReadModelLineageWalker;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:GraphWorkspaceQueryService.java
 *
 * 用途:薄读模型桥,把既有 runtime 读取拼装成一张用于展示的规范
 * 项目图。它绝不写状态、绝不调用模型、从不构建或持久化
 * {@code ContextSnapshot},也从不重复实现路线或上下文语义。
 * 它不是第二个 Runtime Kernel。
 *
 * 对每条路线,lineage 从 {@code tipNodeId} 沿 {@code parentNodeId}
 * 指针向上走到根,并以根→tip 的顺序输出。任何生命周期状态的路线
 * (open、superseded、archived、deleted)都可读。节点缺失、节点属于
 * 其他项目、lineage 成环或超深、路线记录的根节点与解析结果不符时,
 * 一律 fail-closed,按内部不变量违例处理。没有 tip 的路线输出空 lineage。
 *
 * 节点跨路线去重(共享节点只出现一次)。路线专属答案保持独立,
 * 绝不按节点合并。替换路线自然展示其父 lineage 加替换节点;仅凭
 * {@code supersedesNodeId} 指向某个节点,绝不会把被取代的目标节点
 * 注入替换路线的 lineage。
 */
@Service
public class GraphWorkspaceQueryService {

    private final ProjectService projectService;
    private final RouteService routeService;
    private final NodeService nodeService;
    private final AnswerService answerService;
    private final RouteHistoryResolver routeHistoryResolver;
    private final NodeRelationRepository relationRepository;

    public GraphWorkspaceQueryService(ProjectService projectService,
                                      RouteService routeService,
                                      NodeService nodeService,
                                      AnswerService answerService,
                                      RouteHistoryResolver routeHistoryResolver,
                                      NodeRelationRepository relationRepository) {
        this.projectService = projectService;
        this.routeService = routeService;
        this.nodeService = nodeService;
        this.answerService = answerService;
        this.routeHistoryResolver = routeHistoryResolver;
        this.relationRepository = relationRepository;
    }

    public GraphWorkspaceView getForProject(UUID projectId) {
        Project project = projectService.getProject(projectId)
                .orElseThrow(() -> GraphWorkspaceQueryException.of(
                        GraphWorkspaceQueryException.Reason.PROJECT_NOT_FOUND, "Project not found"));

        Map<UUID, Node> nodesById = new LinkedHashMap<>();
        List<GraphWorkspaceRouteView> routeViews = new ArrayList<>();
        List<GraphWorkspaceAnswerView> answerViews = new ArrayList<>();
        // 共享状态分歧探测器:一个规范的 Question 节点全项目只能携带
        // 一个不可变的 Answer 身份。如果读模型对同一节点看到两个不同的
        // 有效 Answer id,那就是不变量违例,不是正常的 UI 呈现模式。
        java.util.Map<UUID, UUID> answerIdentityByNode = new java.util.HashMap<>();
        // 下面"已答/未答分歧"检查的成员表:对一个规范 Question 节点,
        // 哪些路线把它纳入了 lineage,其中哪些路线为它解析出了有效
        // Answer。只比较不可变的 Answer id——绝不比较答案文本。
        java.util.Map<UUID, java.util.Set<UUID>> routesByNode = new java.util.HashMap<>();
        java.util.Map<UUID, java.util.Set<UUID>> answeredRoutesByNode = new java.util.HashMap<>();

        for (Route route : routeService.listRoutes(projectId)) {
            List<Node> lineage = resolveLineage(project.id(), route);
            List<UUID> lineageNodeIds = lineage.stream().map(Node::id).toList();
            lineage.forEach(node -> nodesById.putIfAbsent(node.id(), node));
            for (UUID lineageNodeId : lineageNodeIds) {
                routesByNode.computeIfAbsent(lineageNodeId, k -> new java.util.HashSet<>())
                        .add(route.id());
            }
            List<com.specagent.workspace.answer.Answer> answers = routeHistoryResolver == null
                    ? answerService.findAnswersForRouteAndNodeIds(route.id(), lineageNodeIds)
                    : routeHistoryResolver.resolveEffectiveAnswers(route.id(), lineageNodeIds);
            for (com.specagent.workspace.answer.Answer answer : answers) {
                UUID existing = answerIdentityByNode.putIfAbsent(answer.nodeId(), answer.id());
                if (existing != null && !existing.equals(answer.id())) {
                    throw GraphWorkspaceQueryException.of(
                            GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION,
                            "SHARED_STATE_DIVERGENCE: canonical Question " + answer.nodeId()
                                    + " resolves to multiple effective Answer identities");
                }
                answeredRoutesByNode
                        .computeIfAbsent(answer.nodeId(), k -> new java.util.HashSet<>())
                        .add(route.id());
                answerViews.add(GraphWorkspaceAnswerView.from(
                        answer, route.id(), !route.id().equals(answer.routeId())));
            }
            routeViews.add(GraphWorkspaceRouteView.from(route, project.activeRouteId(), lineageNodeIds));
        }

        // Fail-closed:一个规范 Question 节点在每条包含它的路线上都必须
        // 是一致的答案状态。若它在至少一条路线上有答案、却在另一条上
        // 没有答案(部分、分歧的 lineage 状态),就是共享状态分歧,
        // 而不是 UI 模式。
        for (java.util.Map.Entry<UUID, java.util.Set<UUID>> entry : answeredRoutesByNode.entrySet()) {
            UUID nodeId = entry.getKey();
            java.util.Set<UUID> unansweredRoutes = new java.util.HashSet<>(
                    routesByNode.getOrDefault(nodeId, java.util.Set.of()));
            unansweredRoutes.removeAll(entry.getValue());
            if (!unansweredRoutes.isEmpty()) {
                throw GraphWorkspaceQueryException.of(
                        GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION,
                        "SHARED_STATE_DIVERGENCE: canonical Question " + nodeId
                                + " is answered in some routes but unanswered in others");
            }
        }

        // 悬浮草稿不属于任何路线 lineage;它们仍是独立可见的图内容,
        // 必须出现在工作区视图里。
        nodeService.listProject(project.id()).stream()
                .filter(node -> !node.isRetracted())
                .forEach(node -> nodesById.putIfAbsent(node.id(), node));

        return new GraphWorkspaceView(
                project.id(), project.activeRouteId(), List.copyOf(routeViews),
                nodesById.values().stream()
                        .filter(node -> !node.isRetracted())
                        .map(GraphWorkspaceNodeView::from).toList(),
                List.copyOf(answerViews),
                relationRepository.findActiveByProject(projectId).stream()
                        .map(GraphWorkspaceRelationView::from).toList());
    }

    private List<Node> resolveLineage(UUID projectId, Route route) {
        if (route.tipNodeId() == null) {
            if (route.rootNodeId() != null) {
                throw GraphWorkspaceQueryException.of(
                        GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION,
                        "Route has a null tip but a non-null root node");
            }
            return List.of();
        }

        List<Node> rootToTip;
        try {
            rootToTip = ReadModelLineageWalker.walk(route.tipNodeId(), nodeService::getNode);
        } catch (ReadModelLineageWalker.LineageTraversalException ex) {
            throw GraphWorkspaceQueryException.of(
                    GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION, ex.getMessage());
        }

        for (Node node : rootToTip) {
            if (!node.projectId().equals(projectId)) {
                // Fail-closed:外来节点本身以及它之后的任何节点,
                // 都不允许出现在响应里。
                throw GraphWorkspaceQueryException.of(
                        GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION,
                        "A node in the route lineage belongs to another project");
            }
        }

        if (route.rootNodeId() == null) {
            throw GraphWorkspaceQueryException.of(
                    GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION,
                    "Route has a tip node but no root node");
        }
        if (!route.rootNodeId().equals(rootToTip.get(0).id())) {
            throw GraphWorkspaceQueryException.of(
                    GraphWorkspaceQueryException.Reason.INVARIANT_VIOLATION,
                    "Route root node does not match the resolved lineage");
        }
        return List.copyOf(rootToTip);
    }
}
