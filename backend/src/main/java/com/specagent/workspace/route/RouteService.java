package com.specagent.workspace.route;

import com.specagent.common.Ids;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeKind;
import com.specagent.workspace.node.NodeOption;
import com.specagent.workspace.node.NodeRepository;
import com.specagent.workspace.node.NodeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 文件名:RouteService.java
 *
 * 用途:管理显式探索路线:路线的生命周期状态、活跃路线指针,以及
 * 确定性的路线控制操作(分叉、重新回答、重新生成)。
 *
 * 生命周期状态为 {@code open | superseded | archived | deleted}。
 * 活跃路线由 {@code Project.activeRouteId} 记录,绝不用路线状态表达。
 * 本服务是确定性的,不调用任何模型。
 */
@Service
public class RouteService {

    private final RouteRepository routeRepository;
    private final ProjectActiveRoutePort projectPort;
    private final NodeRepository nodeRepository;
    private final NodeService nodeService;
    private final RouteHistoryResolver routeHistoryResolver;
    private final RouteGraphSupportPort graphSupport;
    private final RouteMembershipProjectionPort routeMembershipProjection;

    public RouteService(RouteRepository routeRepository,
                        ProjectActiveRoutePort projectPort,
                         NodeRepository nodeRepository,
                         NodeService nodeService,
                         RouteHistoryResolver routeHistoryResolver,
                         RouteGraphSupportPort graphSupport,
                         RouteMembershipProjectionPort routeMembershipProjection) {
        this.routeRepository = routeRepository;
        this.projectPort = projectPort;
        this.nodeRepository = nodeRepository;
        this.nodeService = nodeService;
        this.routeHistoryResolver = routeHistoryResolver;
        this.graphSupport = graphSupport;
        this.routeMembershipProjection = routeMembershipProjection;
    }

    public Route createRoute(UUID projectId, RouteLifecycleStatus status, String label) {
        UUID routeId = Ids.random();
        Instant now = Instant.now();
        Route route = new Route(routeId, projectId, null, null, status, label,
                null, null, null, null, now, now);
        routeRepository.save(route);
        return route;
    }

    public void updateTip(UUID routeId, UUID tipNodeId, UUID rootNodeId) {
        routeRepository.updateTipAndRoot(routeId, tipNodeId, rootNodeId, Instant.now());
    }

    private void markRouteSuperseded(UUID routeId) {
        routeRepository.updateLifecycle(routeId, RouteLifecycleStatus.SUPERSEDED, Instant.now());
    }

    private void refreshRouteAffectedSources(UUID projectId,
                                             UUID routeId,
                                             UUID lineageTipNodeId) {
        List<UUID> lineageRoots = lineageTipNodeId == null
                ? List.of()
                : routeHistoryResolver.resolveLineage(lineageTipNodeId);
        routeMembershipProjection.refreshRouteAffectedSources(projectId, routeId, lineageRoots);
    }

    /**
     * 裸的生命周期状态迁移:不带活跃指针的副作用,也不写操作日志。
     * 这是面向用户的生命周期命令(需要记日志)与撤销/重做补偿
     * (绝不能记日志——补偿作用于既有日志条目,不是新的用户操作)
     * 共享的变更核心。活跃路线指针的维护由调用方自行负责。
     */
    @Transactional
    public void transitionLifecycle(UUID projectId, UUID routeId, RouteLifecycleStatus target) {
        projectPort.lockProject(projectId);
        Route route = requireRouteInProject(projectId, routeId);
        requireTransition(route, target);
        routeRepository.updateLifecycle(routeId, target, Instant.now());
    }

    /** 供撤销/重做补偿使用的活跃路线指针恢复。 */
    @Transactional
    public void setActiveRoutePointer(UUID projectId, UUID routeId) {
        projectPort.lockProject(projectId);
        projectPort.updateActiveRoute(projectId, routeId, Instant.now());
        assertActiveRouteInvariant(projectId);
    }

    /** 分支/探索类变更的合法显式来源路线。 */
    public Route requireExplorationSource(UUID projectId, UUID sourceRouteId) {
        Route route = requireRouteInProject(projectId, sourceRouteId);
        if (route.lifecycleStatus() != RouteLifecycleStatus.OPEN
                && route.lifecycleStatus() != RouteLifecycleStatus.SUPERSEDED) {
            throw new IllegalStateException("Only an OPEN or SUPERSEDED route can be an exploration source");
        }
        return route;
    }

    public Optional<Route> getRoute(UUID routeId) {
        return routeRepository.findById(routeId);
    }

    public List<Route> listRoutes(UUID projectId) {
        return routeRepository.findByProject(projectId);
    }

    /**
     * 设置项目的活跃路线。路线必须存在、属于该项目且处于 {@code OPEN}
     * 状态。活跃路线只由 {@code Project.activeRouteId} 表达;
     * 路线的生命周期状态绝不会被改成 {@code active}。
     */
    @Transactional
    public void setActiveRoute(UUID projectId, UUID routeId) {
        projectPort.lockProject(projectId);
        Route route = requireRouteInProject(projectId, routeId);
        if (route.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
            throw new IllegalStateException(
                    "Only an OPEN route can become active: " + routeId
                            + " is " + route.lifecycleStatus().code());
        }
        projectPort.updateActiveRoute(projectId, routeId, Instant.now());
        assertActiveRouteInvariant(projectId);
    }

    /**
     * 归档一条 open 路线。若被归档的是项目当前活跃路线,则清空活跃指针。
     * 不删除任何节点、答案、补丁或共享祖先,也不会隐式激活其他路线。
     */
    @Transactional
    public void archiveRoute(UUID projectId, UUID routeId) {
        projectPort.lockProject(projectId);
        Route route = requireRouteInProject(projectId, routeId);
        requireTransition(route, RouteLifecycleStatus.ARCHIVED);
        UUID previousActiveRouteId = currentActiveRouteId(projectId);
        transitionLifecycle(projectId, routeId, RouteLifecycleStatus.ARCHIVED);
        clearActiveRouteIfMatches(projectId, routeId);
        assertActiveRouteInvariant(projectId);
        appendLifecycleOperation(projectId, routeId, route.lifecycleStatus(),
                previousActiveRouteId);
    }

    /**
     * 软删除路线:标记为 {@code DELETED},历史数据保留。若被删除的是
     * 项目当前活跃路线,则清空活跃指针。
     */
    @Transactional
    public void softDeleteRoute(UUID projectId, UUID routeId) {
        projectPort.lockProject(projectId);
        Route route = requireRouteInProject(projectId, routeId);
        requireTransition(route, RouteLifecycleStatus.DELETED);
        UUID previousActiveRouteId = currentActiveRouteId(projectId);
        transitionLifecycle(projectId, routeId, RouteLifecycleStatus.DELETED);
        clearActiveRouteIfMatches(projectId, routeId);
        assertActiveRouteInvariant(projectId);
        appendLifecycleOperation(projectId, routeId, route.lifecycleStatus(),
                previousActiveRouteId);
    }

    /**
     * 显式地把一条 archived、deleted 或 superseded 路线恢复为
     * {@code OPEN},并使其成为项目的活跃路线。
     */
    @Transactional
    public void restoreRoute(UUID projectId, UUID routeId) {
        projectPort.lockProject(projectId);
        Route route = requireRouteInProject(projectId, routeId);
        requireTransition(route, RouteLifecycleStatus.OPEN);
        UUID previousActiveRouteId = currentActiveRouteId(projectId);
        transitionLifecycle(projectId, routeId, RouteLifecycleStatus.OPEN);
        projectPort.updateActiveRoute(projectId, routeId, Instant.now());
        assertActiveRouteInvariant(projectId);
        appendLifecycleOperation(projectId, routeId, route.lifecycleStatus(),
                previousActiveRouteId);
    }

    private UUID currentActiveRouteId(UUID projectId) {
        return projectPort.findActiveRouteId(projectId).orElse(null);
    }

    private void appendLifecycleOperation(UUID projectId, UUID routeId,
                                          RouteLifecycleStatus fromStatus,
                                          UUID previousActiveRouteId) {
        graphSupport.appendRouteOperation(projectId, RouteOperationKind.ROUTE_LIFECYCLE, List.of(routeId),
                Map.of("routeId", routeId.toString(),
                       "fromStatus", fromStatus.code(),
                       "previousActiveRouteId",
                       previousActiveRouteId == null ? "" : previousActiveRouteId.toString()),
                Map.of("routeId", routeId.toString(),
                       "toStatus", routeRepository.findById(routeId)
                               .map(r -> r.lifecycleStatus().code())
                               .orElse(RouteLifecycleStatus.DELETED.code())));
    }

    /**
     * 从历史节点分叉出一条新的路线视图。分叉路线指向同一份不可变节点
     * lineage:root 保持来源路线的 root,tip 是来源节点,
     * {@code createdFromNodeId} 记录分叉起点。不复制任何节点、答案、补丁
     * 或兄弟路线,旧路线不被修改。新路线成为项目的活跃路线。
     *
     * 显式来源的分叉:分支点上已接受的答案会作为不可变引用冻结进
     * 新路线的前缀。
     */
    @Transactional
    public Route forkFromNode(UUID projectId, UUID sourceRouteId, UUID sourceNodeId, String label) {
        // 与 archive/restore/activate 及图变更串行化:分叉要读来源路线的
        // 生命周期并创建新的活跃路线,所以必须在任何状态读取之前持有
        // 项目行锁(顺序:project → node/route → mutation)。
        projectPort.lockProject(projectId);
        Node sourceNode = nodeRepository.findById(sourceNodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + sourceNodeId));
        if (!sourceNode.projectId().equals(projectId)) {
            throw new IllegalArgumentException(
                    "Node " + sourceNodeId + " does not belong to project " + projectId);
        }

        Route sourceRoute = requireExplorationSource(projectId, sourceRouteId);
        graphSupport.validateRouteProvenance(sourceRouteId);
        List<UUID> sourceLineage = routeHistoryResolver.resolveLineage(sourceRoute.tipNodeId());
        // 分支点必须是本路线的成员:谱系上的节点,或挂在谱系节点下的派生
        // 知识/资源(它们不出现在 tip 父链上,但属于本路线)。
        if (!routeHistoryResolver.belongsToRoute(sourceRoute, sourceNodeId)) {
            throw new IllegalArgumentException(
                    "Node is not on the explicit source route: " + sourceNodeId);
        }
        // 只有可回答的问题节点才要求"已回答才能分叉":从一个未回答的问题处分叉
        // 会把悬而未决的问题埋进分支。知识/资源节点没有可回答状态,天然可以
        // 作为分支点(继续生成问题的入口)。
        if (sourceNode.kind() == NodeKind.INTERACTION
                && routeHistoryResolver.resolveEffectiveAnswers(sourceRouteId, sourceLineage).stream()
                        .noneMatch(answer -> answer.nodeId().equals(sourceNodeId))) {
            throw new IllegalStateException("Fork branch point has no finalized answer: " + sourceNodeId);
        }

        UUID routeId = Ids.random();
        Instant now = Instant.now();
        Route forkRoute = new Route(routeId, projectId, sourceRoute.rootNodeId(), sourceNodeId,
                RouteLifecycleStatus.OPEN, effectiveLabel(projectId, RouteBranchType.FORK, label), sourceNodeId, null, null, null,
                RouteBranchType.FORK, sourceRouteId, sourceNodeId, now, now);
        routeRepository.save(forkRoute);
        routeHistoryResolver.snapshotInheritedPrefix(routeId, sourceRouteId, sourceNodeId, true);
        refreshRouteAffectedSources(projectId, routeId, sourceNodeId);
        UUID previousActiveRouteId = currentActiveRouteId(projectId);
        projectPort.updateActiveRoute(projectId, routeId, now);
        graphSupport.appendRouteOperation(projectId, RouteOperationKind.ROUTE_FORK, List.of(routeId),
                Map.of("routeId", routeId.toString(),
                       "sourceRouteId", sourceRouteId.toString(),
                       "previousActiveRouteId",
                       previousActiveRouteId == null ? "" : previousActiveRouteId.toString()),
                Map.of("routeId", routeId.toString(),
                       "sourceRouteId", sourceRouteId.toString(),
                       "branchAtNodeId", sourceNodeId.toString(),
                       "tipNodeId", sourceNodeId.toString()));
        return forkRoute;
    }

    /**
     * 从一个游离的(不属于任何路线的)节点启动一条全新的独立路线——
     * 即"想法继续生成问题"入口:该想法成为新路线的 root 和 tip,
     * 下一份问题草稿锚定在它上面,新路线成为项目的活跃路线。
     * 节点保持自身的 id、kind 与内容,绝不复制;来源路线(若该节点在
     * 画布上挂在某条路线之下)不受影响。节点必须尚未属于任何路线 lineage。
     */
    @Transactional
    public Route startRouteFromNode(UUID projectId, UUID nodeId, String label) {
        // 与 fork 一样串行化:任何读取/变更前先拿项目锁。
        projectPort.lockProject(projectId);
        Node node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));
        if (!node.projectId().equals(projectId)) {
            throw new IllegalArgumentException(
                    "Node " + nodeId + " does not belong to project " + projectId);
        }
        if (node.kind() == NodeKind.INTERACTION) {
            throw new IllegalArgumentException(
                    "Only a floating knowledge/resource node can start a route: " + nodeId);
        }
        for (Route candidate : routeRepository.findByProject(projectId)) {
            if (candidate.lifecycleStatus() == RouteLifecycleStatus.DELETED) {
                continue;
            }
            if (routeHistoryResolver.belongsToRoute(candidate, nodeId)) {
                throw new IllegalStateException(
                        "Node already belongs to a route lineage: " + nodeId);
            }
        }

        UUID routeId = Ids.random();
        Instant now = Instant.now();
        Route route = new Route(routeId, projectId, nodeId, nodeId,
                RouteLifecycleStatus.OPEN, effectiveLabel(projectId, null, label),
                null, null, null, null, now, now);
        routeRepository.save(route);
        refreshRouteAffectedSources(projectId, routeId, nodeId);
        UUID previousActiveRouteId = currentActiveRouteId(projectId);
        projectPort.updateActiveRoute(projectId, routeId, now);
        graphSupport.appendRouteOperation(projectId, RouteOperationKind.ROUTE_START, List.of(routeId),
                Map.of("routeId", routeId.toString(),
                       "previousActiveRouteId",
                       previousActiveRouteId == null ? "" : previousActiveRouteId.toString()),
                Map.of("routeId", routeId.toString(),
                       "rootNodeId", nodeId.toString(),
                       "tipNodeId", nodeId.toString()));
        return route;
    }

    /**
     * 创建一条重新回答(Re-answer)路线,其 tip 是一个全新的 Question
     * 节点。旧 Question 绝不被复用:它的不可变语义(question、purpose、
     * options、allowFreeAnswer)被复制到一个共享旧父节点的新规范 id 上;
     * 继承前缀只冻结旧 Question 的祖先(旧 Answer 仍留在来源路线);
     * 来源路线及其 Answers 完全不动。
     */
    @Transactional
    public Route reanswerFromNode(UUID projectId,
                                  UUID sourceRouteId,
                                  UUID targetNodeId,
                                  String label) {
        projectPort.lockProject(projectId);
        Node targetNode = requireNodeInProject(projectId, targetNodeId);
        Route sourceRoute = requireExplorationSource(projectId, sourceRouteId);
        List<UUID> sourceLineage = routeHistoryResolver.resolveLineage(sourceRoute.tipNodeId());
        requireLineageContains(sourceLineage, targetNodeId);
        if (routeHistoryResolver.resolveEffectiveAnswers(sourceRouteId, sourceLineage).stream()
                .noneMatch(answer -> answer.nodeId().equals(targetNodeId))) {
            throw new IllegalStateException("Re-answer target has no finalized answer: " + targetNodeId);
        }
        graphSupport.validateRouteProvenance(sourceRouteId);

        UUID routeId = Ids.random();
        Instant now = Instant.now();
        // 新路线的历史从旧 target 的位置开始:root 保持来源 root,tip
        // 暂时指向旧 target,让冻结的继承前缀有锚点;随后克隆出的
        // Question 把 tip 推进到它自己的规范 id 上。
        Route route = new Route(routeId, projectId,
                targetNode.parentNodeId() == null ? null : sourceRoute.rootNodeId(),
                targetNodeId,
                RouteLifecycleStatus.OPEN, effectiveLabel(projectId, RouteBranchType.REANSWER, label),
                targetNodeId, null, null, null,
                RouteBranchType.REANSWER, sourceRouteId, targetNodeId, now, now);
        routeRepository.save(route);
        // 继承前缀不含旧 target 的答案:重新回答的问题重新进入等待状态。
        routeHistoryResolver.snapshotInheritedPrefix(routeId, sourceRouteId, targetNodeId, false);
        Node clonedNode = nodeService.createReanswerNode(
                projectId, routeId, targetNode.parentNodeId(),
                targetNode.question(), targetNode.purpose(), targetNode.options(),
                targetNode.allowFreeAnswer(), targetNode.allowMultiSelect());
        refreshRouteAffectedSources(projectId, routeId, targetNode.parentNodeId());
        UUID previousActiveRouteId = currentActiveRouteId(projectId);
        projectPort.updateActiveRoute(projectId, routeId, now);
        graphSupport.appendRouteOperation(projectId, RouteOperationKind.ROUTE_REANSWER, List.of(routeId, clonedNode.id()),
                Map.of("routeId", routeId.toString(),
                       "sourceRouteId", sourceRouteId.toString(),
                       "previousActiveRouteId",
                       previousActiveRouteId == null ? "" : previousActiveRouteId.toString()),
                Map.of("routeId", routeId.toString(),
                       "sourceRouteId", sourceRouteId.toString(),
                       "clonedNodeId", clonedNode.id().toString(),
                       "replacedNodeId", targetNodeId.toString(),
                       "tipNodeId", clonedNode.id().toString()));
        return routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalStateException("Re-answer route missing after creation: " + routeId));
    }

    /**
     * 提交一个已被接受的 replacement(换题)提案。本方法是唯一创建规范
     * replacement 历史的地方:提案解析、reflection 与校验都在进入此事务
     * 之前完成。
     *
     * {@code expectedSourceRouteTip} 冻结决策所依据的来源 tip。调用方
     * 在本事务之前捕获它;事务内部在持有项目锁之后重新读取来源路线并
     * 重新校验期望的 tip,从而封死"先检查后执行"的窗口,防止并发的
     * continuation 在决策与提交之间推进了来源 tip。
     */
    @Transactional
    public RegenerateResult commitReplacementFromNode(UUID projectId,
                                                      UUID sourceRouteId,
                                                      UUID targetNodeId,
                                                      UUID expectedSourceRouteTip,
                                                      String label,
                                                      String question,
                                                      String purpose,
                                                      List<NodeOption> options,
                                                      boolean allowFreeAnswer) {
        return commitReplacementFromNode(projectId, sourceRouteId, targetNodeId,
                expectedSourceRouteTip, label, question, purpose, options, allowFreeAnswer, false);
    }

    /** 支持多选的 replacement 提交(复制模型的 allowMultiSelect 标志)。 */
    @Transactional
    public RegenerateResult commitReplacementFromNode(UUID projectId,
                                                      UUID sourceRouteId,
                                                      UUID targetNodeId,
                                                      UUID expectedSourceRouteTip,
                                                      String label,
                                                      String question,
                                                      String purpose,
                                                      List<NodeOption> options,
                                                      boolean allowFreeAnswer,
                                                      boolean allowMultiSelect) {
        // 与 archive/restore/activate 及图变更串行化:replacement 会把来源
        // 路线标记为 superseded 并改变活跃路线,所以在任何状态读取之前
        // 先取项目行锁。stale-tip 检查在该锁内进行,并发的 continuation
        // 不可能在决策与提交之间推进来源 tip。
        projectPort.lockProject(projectId);
        Node targetNode = requireNodeInProject(projectId, targetNodeId);
        if (targetNode.parentNodeId() == null) {
            throw new IllegalStateException("Root node replacement is not supported");
        }
        Route sourceRoute = requireExplorationSource(projectId, sourceRouteId);
        graphSupport.validateRouteProvenance(sourceRouteId);
        // 在项目锁内重新校验:自决策被冻结以来来源没有移动——tip 必须仍
        // 精确等于期望值,且 target 仍位于这条确切的来源 lineage 上。
        if (!java.util.Objects.equals(sourceRoute.tipNodeId(), expectedSourceRouteTip)) {
            throw new IllegalStateException(
                    "Source route tip moved after the replacement snapshot: expected "
                            + expectedSourceRouteTip + ", current " + sourceRoute.tipNodeId());
        }
        List<UUID> sourceLineage = routeHistoryResolver.resolveLineage(sourceRoute.tipNodeId());
        requireLineageContains(sourceLineage, targetNodeId);
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Replacement question must not be blank");
        }
        if (normalize(question).equals(normalize(targetNode.question()))) {
            throw new IllegalArgumentException("Replacement question must differ from the rejected question");
        }

        UUID replacementRouteId = Ids.random();
        Instant now = Instant.now();
        Route replacementRoute = new Route(
                replacementRouteId,
                projectId,
                sourceRoute.rootNodeId(),
                null,
                RouteLifecycleStatus.OPEN,
                effectiveLabel(projectId, RouteBranchType.REGENERATE, label),
                targetNode.parentNodeId(),
                sourceRouteId,
                targetNodeId,
                null,
                RouteBranchType.REGENERATE,
                sourceRouteId,
                targetNodeId,
                now,
                now);
        routeRepository.save(replacementRoute);

        routeHistoryResolver.snapshotInheritedPrefix(
                replacementRouteId, sourceRouteId, targetNode.parentNodeId(), true);
        Node replacementNode = nodeService.createReplacementNode(
                projectId, replacementRouteId, targetNode.parentNodeId(), targetNodeId,
                question.trim(), purpose, options == null ? List.of() : options, allowFreeAnswer,
                allowMultiSelect);
        refreshRouteAffectedSources(projectId, replacementRouteId, targetNode.parentNodeId());

        boolean sourceWasOpen = sourceRoute.lifecycleStatus() == RouteLifecycleStatus.OPEN;
        UUID previousActiveRouteId = currentActiveRouteId(projectId);
        if (sourceRoute.lifecycleStatus() == RouteLifecycleStatus.OPEN) {
            markRouteSuperseded(sourceRouteId);
        }
        projectPort.updateActiveRoute(projectId, replacementRouteId, now);

        graphSupport.appendRouteOperation(projectId, RouteOperationKind.ROUTE_REGENERATE,
                List.of(replacementRouteId, replacementNode.id()),
                Map.<String, Object>of("routeId", replacementRouteId.toString(),
                       "sourceRouteId", sourceRouteId.toString(),
                       "sourceWasOpen", sourceWasOpen,
                       "previousActiveRouteId",
                       previousActiveRouteId == null ? "" : previousActiveRouteId.toString()),
                Map.of("routeId", replacementRouteId.toString(),
                       "sourceRouteId", sourceRouteId.toString(),
                       "replacementNodeId", replacementNode.id().toString(),
                       "replacedNodeId", targetNodeId.toString(),
                       "tipNodeId", replacementNode.id().toString()));

        Route updatedSource = routeRepository.findById(sourceRouteId)
                .orElseThrow(() -> new IllegalStateException("Source route not found after replacement"));
        Route updatedReplacement = routeRepository.findById(replacementRouteId)
                .orElseThrow(() -> new IllegalStateException("Replacement route not found after commit"));
        return new RegenerateResult(updatedSource, updatedReplacement, replacementNode);
    }

    /**
     * 判断节点是否位于路线的 lineage 上:即从 {@code tipNodeId} 沿
     * {@code parentNodeId} 指针走到根的链。这里刻意忽略 replacement 关系——
     * replacement 节点永远不会进入它所取代路线的正常 lineage。
     */
    private boolean lineageContains(Route route, UUID nodeId) {
        if (nodeId == null || route.tipNodeId() == null) {
            return false;
        }

        UUID current = route.tipNodeId();
        Set<UUID> visited = new HashSet<>();
        int guard = 0;

        while (current != null && !visited.contains(current)) {
            if (current.equals(nodeId)) {
                return true;
            }

            visited.add(current);
            Node currentNode = nodeRepository.findById(current).orElse(null);
            current = currentNode != null ? currentNode.parentNodeId() : null;

            if (++guard > 10_000) {
                throw new IllegalStateException("Node lineage exceeds maximum depth");
            }
        }

        return false;
    }

    private Route requireRouteInProject(UUID projectId, UUID routeId) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalArgumentException("Route not found: " + routeId));
        if (!route.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Route " + routeId + " does not belong to project " + projectId);
        }
        return route;
    }

    private Route requireOpenRouteInProject(UUID projectId, UUID routeId) {
        Route route = requireRouteInProject(projectId, routeId);
        if (route.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
            throw new IllegalStateException("Only an OPEN route can be a branch source: " + routeId);
        }
        return route;
    }

    private Node requireNodeInProject(UUID projectId, UUID nodeId) {
        Node node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));
        if (!node.projectId().equals(projectId)) {
            throw new IllegalArgumentException("Node " + nodeId + " does not belong to project " + projectId);
        }
        return node;
    }

    private void requireLineageContains(List<UUID> lineage, UUID nodeId) {
        if (!lineage.contains(nodeId)) {
            throw new IllegalArgumentException("Node is not on the explicit source route: " + nodeId);
        }
    }

    private void requireTransition(Route route, RouteLifecycleStatus target) {
        boolean allowed = switch (route.lifecycleStatus()) {
            case OPEN -> target == RouteLifecycleStatus.ARCHIVED || target == RouteLifecycleStatus.DELETED;
            case SUPERSEDED -> target == RouteLifecycleStatus.OPEN
                    || target == RouteLifecycleStatus.ARCHIVED || target == RouteLifecycleStatus.DELETED;
            case ARCHIVED -> target == RouteLifecycleStatus.OPEN || target == RouteLifecycleStatus.DELETED;
            case DELETED -> target == RouteLifecycleStatus.OPEN;
        };
        if (!allowed) {
            throw new IllegalStateException("Illegal route lifecycle transition");
        }
    }

    private String effectiveLabel(UUID projectId, RouteBranchType branchType, String requested) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        long count = routeRepository.findByProject(projectId).stream()
                .filter(route -> route.branchType() == branchType)
                .count();
        String prefix = branchType == null ? "想法路线" : switch (branchType) {
            case FORK -> "分支路线";
            case REANSWER -> "重新回答路线";
            case REGENERATE -> "换题路线";
            case CONTINUATION -> "探索分支";
        };
        return prefix + " " + (count + 1);
    }

    private void clearActiveRouteIfMatches(UUID projectId, UUID routeId) {
        // 端口契约:项目不存在时在端口内抛异常;项目存在但没有活跃路线
        // 时这里得到 null(保持原始语义)。
        UUID activeRouteId = projectPort.findActiveRouteId(projectId).orElse(null);
        if (activeRouteId != null && activeRouteId.equals(routeId)) {
            projectPort.updateActiveRoute(projectId, null, Instant.now());
        }
    }

    /**
     * 以 fail-closed 方式执行活跃路线指针的不变量检查,在项目行锁仍被持有
     * 时、每次生命周期/活跃路线变更之后调用:{@code activeRouteId} 要么为
     * null,要么指向一条存在、属于本项目且生命周期为 {@code OPEN} 的路线。
     *
     * 这是在上述显式维护之上的一道安全网——当活跃指针即将悬空时,
     * 它绝不会自动挑选其他路线。违反不变量说明本服务出现回归或数据早已
     * 损坏,会以稳定的 {@code IllegalStateException} 浮出。
     */
    private void assertActiveRouteInvariant(UUID projectId) {
        // 端口契约:项目不存在时在端口内抛异常;项目存在但没有活跃路线
        // 时这里得到 null(保持原始语义)。
        UUID activeRouteId = projectPort.findActiveRouteId(projectId).orElse(null);
        if (activeRouteId == null) {
            return;
        }
        Route active = routeRepository.findById(activeRouteId)
                .orElseThrow(() -> new IllegalStateException(
                        "ACTIVE_ROUTE_DANGLING: project " + projectId
                                + " activeRouteId " + activeRouteId + " references a missing route"));
        if (!active.projectId().equals(projectId)) {
            throw new IllegalStateException(
                    "ACTIVE_ROUTE_CROSS_PROJECT: project " + projectId
                            + " activeRouteId " + activeRouteId + " belongs to another project");
        }
        if (active.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
            throw new IllegalStateException(
                    "ACTIVE_ROUTE_NOT_OPEN: project " + projectId
                            + " activeRouteId " + activeRouteId + " is not OPEN: "
                            + active.lifecycleStatus().code());
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase();
    }
}
