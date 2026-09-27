package com.specagent.workspace.graph;

import com.specagent.workspace.node.KnowledgeStatus;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeAuthorKind;
import com.specagent.workspace.node.NodeKind;
import com.specagent.workspace.node.NodeRepository;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.ProjectRepository;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteBranchType;
import com.specagent.workspace.route.RouteHistoryResolver;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.RouteMembershipProjectionPort;
import com.specagent.workspace.route.RouteRepository;
import com.specagent.workspace.route.RouteService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:GraphCommandService.java
 *
 * 用途:图工作区模型的事务化变更命令层。每个用户可见的持久变更都经由
 * 一个命令方法完成:(1) 按图不变量校验,(2) 执行变更,(3) 在同一事务里
 * 追加一条类型化的 {@link GraphOperation}。外部 agent 动作协议保持通用:
 * {@code CREATE_NODE} / {@code CONNECT_NODE} 在策略审批通过后映射到这里
 * 的命令,本层不存在业务专属的命令名。
 *
 * 强制的不变量:禁止向历史插入(从非 tip 节点续写必须创建显式分支路线)、
 * 不可变答案永不被修改或删除、共享节点身份永不被克隆、路线歧义绝不靠
 * "回退到活跃/第一条/最新路线"来消解。
 */
@Service
public class GraphCommandService {

    private final NodeService nodeService;
    private final NodeRepository nodeRepository;
    private final RouteService routeService;
    private final RouteRepository routeRepository;
    private final RouteHistoryResolver routeHistoryResolver;
    private final NodeRelationRepository relationRepository;
    private final GraphOperationRepository operationRepository;
    private final GraphInvariantValidator invariantValidator;
    private final ProjectRepository projectRepository;
    private final RouteMembershipProjectionPort routeMembershipProjection;

    public GraphCommandService(NodeService nodeService,
                               NodeRepository nodeRepository,
                               RouteService routeService,
                               RouteRepository routeRepository,
                               RouteHistoryResolver routeHistoryResolver,
                               NodeRelationRepository relationRepository,
                               GraphOperationRepository operationRepository,
                               GraphInvariantValidator invariantValidator,
                               ProjectRepository projectRepository,
                               RouteMembershipProjectionPort routeMembershipProjection) {
        this.nodeService = nodeService;
        this.nodeRepository = nodeRepository;
        this.routeService = routeService;
        this.routeRepository = routeRepository;
        this.routeHistoryResolver = routeHistoryResolver;
        this.relationRepository = relationRepository;
        this.operationRepository = operationRepository;
        this.invariantValidator = invariantValidator;
        this.projectRepository = projectRepository;
        this.routeMembershipProjection = routeMembershipProjection;
    }

    /**
     * 在空路线上创建第一个(根)草稿节点。这是新项目的零模型调用入口:
     * 用户先写内容,再引入任何 agent。
     */
    @Transactional
    public Node createRootDraftNode(UUID projectId,
                                    UUID routeId,
                                    String subtype,
                                    Map<String, Object> content) {
        // 让项目内所有图变更写入方都在同一把 project 行锁下串行
        // (顺序:project → node/route → 变更 → 操作日志),保证并发
        // undo/redo 或语义关系创建不会交错出半应用的图或操作栈。
        projectRepository.lockById(projectId);
        Route route = requireOpenRouteInProject(projectId, routeId);
        if (route.tipNodeId() != null) {
            throw new IllegalStateException(
                    "Route already has content; use a continuation instead of a root node: " + routeId);
        }
        Node node = nodeService.createWorkspaceNode(
                projectId, routeId, null, NodeKind.KNOWLEDGE, subtype, content,
                NodeAuthorKind.USER, KnowledgeStatus.PROPOSED);
        operationRepository.append(projectId, GraphOperation.Actor.USER,
                GraphOperation.Type.CREATE_DRAFT_NODE, List.of(node.id()),
                Map.of("routeId", routeId.toString()),
                Map.of("routeId", routeId.toString(),
                       "nodeId", node.id().toString(),
                       "subtype", node.subtype()));
        return node;
    }

    /**
     * 创建一个独立的(悬浮)用户草稿。节点初始不挂在任何 lineage 上——
     * 路线 tip 永不前进——因此 "+ idea" 不会悄悄改接图。创建上下文的
     * routeId 是可选的:悬浮节点可以完全不依赖 Active 路线创建。该
     * routeId 只作为创建上下文记入操作日志,绝不作为归属记录。撤销/重做
     * 把 {@code floating} 引用视为"无 tip/root 副作用"。
     */
    @Transactional
    public Node createFloatingDraftNode(UUID projectId,
                                        UUID routeId,
                                        String subtype,
                                        Map<String, Object> content) {
        return createFloatingDraftNode(projectId, routeId, NodeKind.KNOWLEDGE, subtype, content);
    }

    /**
     * 与 {@link #createFloatingDraftNode(UUID, UUID, String, Map)} 相同,
     * 但显式指定 kind,因此资源也可以以脱离路线的方式创建。资源是能力的
     * 上下文来源;让 kind 显式传入(而不是从 subtype 推断)使节点的语义
     * 对用户可见,且每种 kind 的 subtype 白名单保持权威。
     */
    @Transactional
    public Node createFloatingDraftNode(UUID projectId,
                                        UUID routeId,
                                        NodeKind kind,
                                        String subtype,
                                        Map<String, Object> content) {
        projectRepository.lockById(projectId);
        if (routeId != null) {
            requireOpenRouteInProject(projectId, routeId);
        }
        Node node = nodeService.createFloatingWorkspaceNode(
                projectId, kind, subtype, content,
                NodeAuthorKind.USER, kind == NodeKind.RESOURCE ? null : KnowledgeStatus.PROPOSED);
        // Node 本身不携带 routeId(它是无路线的)。创建上下文的 routeId
        // 只记入操作日志,不写入持久化的节点行;上下文为 null 也合法。
        Map<String, Object> beforeRefs = routeId == null
                ? Map.of()
                : Map.of("routeId", routeId.toString());
        Map<String, Object> afterRefs = new java.util.LinkedHashMap<>();
        if (routeId != null) {
            afterRefs.put("routeId", routeId.toString());
        }
        afterRefs.put("nodeId", node.id().toString());
        afterRefs.put("subtype", node.subtype());
        afterRefs.put("floating", true);
        operationRepository.append(projectId, GraphOperation.Actor.USER,
                GraphOperation.Type.CREATE_DRAFT_NODE, List.of(node.id()),
                beforeRefs, afterRefs);
        return node;
    }

    /**
     * 把一个已存在的悬浮(无路线)节点接进路线,使其成为路线的新 tip。
     *
     * 这是资源接入"先浮动、再自己连线"的一半:内容先写一次,
     * 归入路线是另一个显式动作。落位遵循共享的按 kind 区分的 tip 语义——
     * 节点可以挂在未回答的问题 tip 之下(作为该待答问题的出处材料),
     * 但只有当不会埋掉可回答的问题时 tip 才前进。节点保留自己的 id、
     * kind 和内容;只有 {@code parent_node_id}(以及 tip 前进时的路线
     * tip)变化,因此该操作可被干净地撤销(undo 即摘线)。
     */
    @Transactional
    public Node connectFloatingNodeToRoute(UUID projectId,
                                           UUID routeId,
                                           UUID nodeId,
                                           UUID parentNodeId) {
        projectRepository.lockById(projectId);
        Route route = requireOpenRouteInProject(projectId, routeId);
        nodeRepository.lockById(nodeId);
        Node node = requireNodeInProject(projectId, nodeId);
        if (node.isRetracted()) {
            throw new IllegalStateException("Node is retracted and cannot be connected: " + nodeId);
        }
        if (node.parentNodeId() != null) {
            throw new IllegalStateException(
                    "Node already belongs to a lineage; disconnect it first: " + nodeId);
        }
        if (route.tipNodeId() == null) {
            if (parentNodeId != null) {
                throw new IllegalStateException(
                        "Route has no content yet; connect the node as its root instead: " + routeId);
            }
        } else {
            if (parentNodeId == null) {
                throw new IllegalStateException(
                        "Route already has content; connect at the current tip instead: " + routeId);
            }
            requireNodeInProject(projectId, parentNodeId);
            if (!route.tipNodeId().equals(parentNodeId)) {
                throw new GraphRuleViolationException("CONNECT_NOT_AT_TIP",
                        "Nodes may only be connected at the current tip, never as a historical branch");
            }
            // 被挂的是资源/知识节点:不可回答,所以即使当前 tip 是一个
            // 尚未回答的问题,也可以挂在它之下。参见按 kind 区分的重载。
            invariantValidator.validateQuestionCanHaveChild(
                    projectId, routeId, parentNodeId, node.kind());
        }
        UUID previousTipNodeId = route.tipNodeId();
        Instant now = Instant.now();
        nodeRepository.updateParent(nodeId, parentNodeId, now);
        // 通过共享的按 kind 区分的语义推进 tip(见 NodeService.advanceRouteTip):
        // 挂在未回答问题之下的知识/资源节点停留在问题之下作为出处——
        // 问题仍是 tip、仍可回答。若在这里无条件推进,会把待答问题
        // 埋掉并让路线断头。
        nodeService.advanceRouteTip(routeId, node);
        refreshRouteAffectedSources(projectId, routeId);
        boolean tipAdvanced = routeRepository.findById(routeId)
                .map(r -> nodeId.equals(r.tipNodeId()))
                .orElse(false);
        operationRepository.append(projectId, GraphOperation.Actor.USER,
                GraphOperation.Type.CONNECT_FLOATING_NODE, List.of(nodeId),
                Map.of("routeId", routeId.toString(),
                       "previousTipNodeId", previousTipNodeId == null ? "" : previousTipNodeId.toString(),
                       "detached", true),
                Map.of("routeId", routeId.toString(),
                       "nodeId", nodeId.toString(),
                       "parentId", parentNodeId == null ? "" : parentNodeId.toString(),
                       "previousTipNodeId", previousTipNodeId == null ? "" : previousTipNodeId.toString(),
                       "tipAdvanced", tipAdvanced));
        return requireNodeInProject(projectId, nodeId);
    }

    /**
     * 把节点从其所在路线摘下,恢复成悬浮节点。可摘的形态有两种:当前
     * tip(摘掉后 tip 重新锚定到该节点的父节点),以及由按 kind 区分的
     * 连接挂在 lineage 下方的出处子节点(只清空它的父指针——tip 不动)。
     * 无论哪种,节点必须是叶子:内容永不被销毁,只解除归属。
     */
    @Transactional
    public Node detachNodeFromRoute(UUID projectId, UUID routeId, UUID nodeId) {
        projectRepository.lockById(projectId);
        Route route = requireOpenRouteInProject(projectId, routeId);
        nodeRepository.lockById(nodeId);
        Node node = requireNodeInProject(projectId, nodeId);
        if (node.parentNodeId() == null) {
            throw new IllegalStateException("Node is already floating: " + nodeId);
        }
        boolean isTip = route.tipNodeId() != null && route.tipNodeId().equals(nodeId);
        if (!isTip) {
            // 出处子节点(由按 kind 区分的连接挂在 lineage 下方的知识/
            // 资源节点):只要其下没有挂别的东西就可摘。INTERACTION 节点
            // 按构造就是链上成员——链中间的问题绝不允许被摘,那会改写
            // 答案历史。父节点必须仍在这条路线的 lineage 上,因此摘线
            // 永远不会跨路线。
            if (node.kind() == NodeKind.INTERACTION) {
                throw new GraphRuleViolationException("NODE_NOT_DETACHABLE",
                        "Only the current tip can be detached: " + nodeId);
            }
            List<UUID> lineage = routeHistoryResolver.resolveLineage(route.tipNodeId());
            if (!lineage.contains(node.parentNodeId())) {
                throw new GraphRuleViolationException("NODE_NOT_DETACHABLE",
                        "Only the current tip (or a node attached below the lineage) can be detached: "
                                + nodeId);
            }
        }
        if (nodeRepository.existsActiveByParentNodeId(nodeId)) {
            throw new GraphRuleViolationException("NODE_NOT_DETACHABLE",
                    "Node has live children and cannot be detached: " + nodeId);
        }
        UUID parentId = node.parentNodeId();
        Instant now = Instant.now();
        nodeRepository.updateParent(nodeId, null, now);
        if (isTip) {
            if (parentId == null) {
                routeRepository.clearTipAndRoot(route.id(), now);
            } else {
                routeRepository.updateTipAndRoot(route.id(), parentId, route.rootNodeId(), now);
            }
        }
        routeMembershipProjection.refreshNodeRouteProvenance(projectId, List.of(nodeId));
        operationRepository.append(projectId, GraphOperation.Actor.USER,
                GraphOperation.Type.DISCONNECT_NODE, List.of(nodeId),
                Map.of("routeId", routeId.toString(),
                       "previousTipNodeId", nodeId.toString(),
                       "parentId", parentId.toString(),
                       "tipDetached", isTip),
                Map.of("routeId", routeId.toString(),
                       "nodeId", nodeId.toString(),
                       "detached", true,
                       "tipDetached", isTip));
        return requireNodeInProject(projectId, nodeId);
    }

    /** 续写命令的结果:新节点加上它落在的那条路线。 */
    public record ContinuationResult(Node node, Route route, boolean branched) {
    }

    /**
     * 从显式路线上的任意节点继续探索。
     *
     * 若源节点是路线 tip,则新节点被追加并推进 tip。若源节点是历史
     * (非 tip)节点,则从该点创建一条显式分支路线——历史 lineage 永不
     * 改写,任何东西都不会插到既有节点之间。分支点之前生效的答案前缀
     * 被冻结为不可变的继承引用,与 fork 完全一致。
     */
    @Transactional
    public ContinuationResult appendContinuation(UUID projectId,
                                                 UUID routeId,
                                                 UUID sourceNodeId,
                                                 String subtype,
                                                 Map<String, Object> content) {
        projectRepository.lockById(projectId);
        Route route = requireOpenRouteInProject(projectId, routeId);
        Node sourceNode = requireNodeInProject(projectId, sourceNodeId);
        requireLineageContains(route, sourceNodeId);
        invariantValidator.validateQuestionCanHaveChild(projectId, routeId, sourceNodeId);

        if (route.tipNodeId().equals(sourceNodeId)) {
            Node node = nodeService.createWorkspaceNode(
                    projectId, routeId, sourceNodeId, NodeKind.KNOWLEDGE, subtype, content,
                    NodeAuthorKind.USER, KnowledgeStatus.PROPOSED);
            operationRepository.append(projectId, GraphOperation.Actor.USER,
                    GraphOperation.Type.APPEND_CONTINUATION, List.of(node.id()),
                    Map.of("routeId", routeId.toString(), "previousTipNodeId", sourceNodeId.toString()),
                    Map.of("routeId", routeId.toString(),
                           "nodeId", node.id().toString(),
                           "parentId", sourceNodeId.toString(),
                           "subtype", node.subtype()));
            return new ContinuationResult(node, routeRepository.findById(routeId).orElse(route), false);
        }

        // 非 tip 源节点:创建显式分支路线;绝不向历史插入。
        Instant now = Instant.now();
        UUID branchRouteId = com.specagent.common.Ids.random();
        invariantValidator.validateRouteProvenance(routeId);
        Route branchRoute = new Route(branchRouteId, projectId, route.rootNodeId(), sourceNodeId,
                RouteLifecycleStatus.OPEN, nextBranchLabel(projectId, "探索分支"),
                sourceNodeId, null, null, null,
                RouteBranchType.CONTINUATION, routeId, sourceNodeId, now, now);
        routeRepository.save(branchRoute);
        routeHistoryResolver.snapshotInheritedPrefix(branchRouteId, routeId, sourceNodeId, true);

        Node node = nodeService.createWorkspaceNode(
                projectId, branchRouteId, sourceNodeId, NodeKind.KNOWLEDGE, subtype, content,
                NodeAuthorKind.USER, KnowledgeStatus.PROPOSED);
        operationRepository.append(projectId, GraphOperation.Actor.USER,
                GraphOperation.Type.CREATE_BRANCH_AND_APPEND, List.of(node.id()),
                Map.of("sourceRouteId", routeId.toString(), "branchAtNodeId", sourceNodeId.toString()),
                Map.of("routeId", branchRouteId.toString(),
                       "sourceRouteId", routeId.toString(),
                       "nodeId", node.id().toString(),
                       "parentId", sourceNodeId.toString(),
                       "subtype", node.subtype()));

        // 用户现在在分支上工作;把它设为活跃路线。
        routeService.setActiveRoute(projectId, branchRouteId);
        return new ContinuationResult(node, routeRepository.findById(branchRouteId).orElse(branchRoute), true);
    }

    /**
     * 挂载一个用户创建的资源节点(FILE/URL/TEXT/…)。
     *
     * 资源可以作为空路线的根,或追加在当前 tip——绝不在历史节点上
     * 分支,也绝不携带知识状态语义。资源是能力的上下文来源(带出处的
     * 有界摘录),不是已确认的结论。
     */
    @Transactional
    public Node attachResource(UUID projectId,
                               UUID routeId,
                               UUID parentNodeId,
                               String subtype,
                               Map<String, Object> content) {
        projectRepository.lockById(projectId);
        Route route = requireOpenRouteInProject(projectId, routeId);
        if (parentNodeId == null) {
            if (route.tipNodeId() != null) {
                throw new IllegalStateException(
                        "Route already has content; attach the resource at the tip instead: " + routeId);
            }
        } else {
            requireNodeInProject(projectId, parentNodeId);
            if (!route.tipNodeId().equals(parentNodeId)) {
                throw new IllegalStateException(
                        "Resources may only be attached at the current tip, never as a historical branch");
            }
            // 与手绘连线相同的按 kind 区分规则:资源可以挂在未回答的
            // 问题 tip 之下(成为该待答问题的出处材料,绝不会埋掉它)。
            invariantValidator.validateQuestionCanHaveChild(projectId, routeId, parentNodeId,
                    NodeKind.RESOURCE);
        }
        Node node = nodeService.createWorkspaceNode(
                projectId, routeId, parentNodeId, NodeKind.RESOURCE, subtype, content,
                NodeAuthorKind.USER, null);
        operationRepository.append(projectId, GraphOperation.Actor.USER,
                GraphOperation.Type.ATTACH_RESOURCE, List.of(node.id()),
                Map.of("routeId", routeId.toString(),
                       "previousTipNodeId", parentNodeId == null ? "" : parentNodeId.toString()),
                Map.of("routeId", routeId.toString(),
                       "nodeId", node.id().toString(),
                       "subtype", node.subtype(),
                       "parentId", parentNodeId == null ? "" : parentNodeId.toString()));
        return node;
    }

    /** 原地编辑一个仍可编辑的用户草稿,并把先前的状态记入日志。 */
    @Transactional
    public Node reviseDraftNode(UUID projectId, UUID nodeId, String subtype, Map<String, Object> content) {
        projectRepository.lockById(projectId);
        Node before = requireNodeInProject(projectId, nodeId);
        if (!before.isUserEditableDraft()) {
            throw new IllegalStateException("Node is not an editable user draft: " + nodeId);
        }
        Node after = nodeService.reviseUserDraft(projectId, nodeId, subtype, content);
        operationRepository.append(projectId, GraphOperation.Actor.USER,
                GraphOperation.Type.EDIT_DRAFT_NODE, List.of(nodeId),
                Map.of("subtype", before.subtype(), "content", before.content()),
                Map.of("subtype", after.subtype(), "content", after.content()));
        return after;
    }

    /**
     * 创建一条语义关系。Origin 记录出处:USER 表示用户显式操作;
     * AGENT 表示只有 Advisor 提案被接受后才会走到这里
     * ({@code createdByProposalId})。
     *
     * 不变量:两端必须是同项目内已持久化、未被撤回的节点;对称类型
     * (RELATED_TO, CONFLICTS_WITH)会把端点顺序规范化,使两个方向都是
     * 同一条事实;DEPENDS_ON 与 DERIVED_FROM 联合构成无环依赖 DAG。
     */
    @Transactional
    public NodeRelation createSemanticRelation(UUID projectId,
                                               UUID sourceNodeId,
                                               UUID targetNodeId,
                                               NodeRelationType type,
                                               NodeRelation.Origin origin,
                                               UUID createdByProposalId,
                                               UUID createdByRunId) {
        // 让本项目内的语义关系创建串行:环校验读取的是项目级激活关系图,
        // 两个并发事务不能都基于同一份旧图做判断(例如 A DEPENDS_ON B 与
        // B DEPENDS_ON A 竞态)。只锁 project 行,互不相关的项目仍然
        // 彼此独立。
        projectRepository.lockById(projectId);
        invariantValidator.validateRelationCreation(projectId, sourceNodeId, targetNodeId, type);
        GraphInvariantValidator.CanonicalEndpoints endpoints =
                GraphInvariantValidator.endpointsCanonicalized(sourceNodeId, targetNodeId, type);
        NodeRelation relation = relationRepository.insertActiveOrThrowDuplicate(
                projectId, endpoints.sourceNodeId(), endpoints.targetNodeId(), type, origin,
                createdByProposalId, createdByRunId);
        operationRepository.append(projectId,
                origin == NodeRelation.Origin.USER ? GraphOperation.Actor.USER : GraphOperation.Actor.AGENT,
                GraphOperation.Type.CREATE_SEMANTIC_RELATION, List.of(relation.id()),
                Map.of(),
                Map.of("relationId", relation.id().toString(),
                       "sourceNodeId", endpoints.sourceNodeId().toString(),
                       "targetNodeId", endpoints.targetNodeId().toString(),
                       "relationType", type.code()));
        return relation;
    }

    /** 应用一次显式的知识状态流转(如 PROPOSED -> CONFIRMED)。 */
    @Transactional
    public Node setKnowledgeStatus(UUID projectId, UUID nodeId, KnowledgeStatus status) {
        projectRepository.lockById(projectId);
        Node before = requireNodeInProject(projectId, nodeId);
        Node after = nodeService.setKnowledgeStatus(projectId, nodeId, status);
        operationRepository.append(projectId, GraphOperation.Actor.USER,
                GraphOperation.Type.SET_KNOWLEDGE_STATUS, List.of(nodeId),
                Map.of("status", before.knowledgeStatus().code()),
                Map.of("status", after.knowledgeStatus().code()));
        return after;
    }

    /** 列出类型化操作日志(供 UI 的撤销/重做入口与审计使用)。 */
    public List<GraphOperation> listOperations(UUID projectId) {
        return operationRepository.findByProject(projectId);
    }

    /** 列出项目的全部激活语义关系。 */
    public List<NodeRelation> listRelations(UUID projectId) {
        return relationRepository.findActiveByProject(projectId);
    }

    private Route requireOpenRouteInProject(UUID projectId, UUID routeId) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalArgumentException("Route not found: " + routeId));
        if (!route.projectId().equals(projectId)) {
            throw new IllegalArgumentException(
                    "Route " + routeId + " does not belong to project " + projectId);
        }
        if (route.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
            throw new IllegalStateException("Route is not open: " + routeId);
        }
        return route;
    }

    private Node requireNodeInProject(UUID projectId, UUID nodeId) {
        Node node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));
        if (!node.projectId().equals(projectId)) {
            throw new IllegalArgumentException(
                    "Node " + nodeId + " does not belong to project " + projectId);
        }
        return node;
    }

    private void requireLineageContains(Route route, UUID nodeId) {
        if (!routeHistoryResolver.resolveLineage(route.tipNodeId()).contains(nodeId)) {
            throw new IllegalArgumentException(
                    "Node is not on the explicit source route: " + nodeId);
        }
    }

    private void refreshRouteAffectedSources(UUID projectId, UUID routeId) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalStateException("Route missing after graph mutation: " + routeId));
        List<UUID> lineageRoots = route.tipNodeId() == null
                ? List.of()
                : routeHistoryResolver.resolveLineage(route.tipNodeId());
        routeMembershipProjection.refreshRouteAffectedSources(projectId, routeId, lineageRoots);
    }

    private String nextBranchLabel(UUID projectId, String prefix) {
        long count = routeRepository.findByProject(projectId).stream()
                .filter(route -> route.branchType() == RouteBranchType.CONTINUATION)
                .count();
        return prefix + " " + (count + 1);
    }
}
