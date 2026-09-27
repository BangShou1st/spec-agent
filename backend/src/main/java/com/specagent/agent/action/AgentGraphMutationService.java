package com.specagent.agent.action;

import com.specagent.workspace.graph.GraphInvariantValidator;
import com.specagent.workspace.graph.GraphOperation;
import com.specagent.workspace.graph.GraphOperationRepository;
import com.specagent.workspace.node.KnowledgeStatus;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeAuthorKind;
import com.specagent.workspace.node.NodeKind;
import com.specagent.workspace.node.NodeOption;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.ProjectRepository;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.RouteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:AgentGraphMutationService.java
 *
 * 用途:agent 驱动的图变更的窄事务边界。
 *
 * 模型/策略链路(Decision 或 Answer 循环)运行在事务和锁之外。只有当
 * 模型与策略全部完成后,变更才进入本 bean,并在一个事务内对照当前图状态
 * 重新校验决策:
 *
 * 1. {@code projectRepository.lockById} —— 所有项目级图写入方都首先
 *       获取的项目行锁(顺序:project → route/node → graph write),
 *       因此自动执行绝不可能与并发的 Undo、续跑或归档交错,
 *       产生"半应用的 tip"。
 * 1. 重新读取并校验路由属于该项目且处于 {@code OPEN} 状态。
 * 2. 重新核对期望锚点(模型做出决策时的 tip)与当前路由 tip:锚点为
 *       null 要求路由仍为空;锚点非 null 则必须仍是 tip。路由已经前进时
 *       fail-closed 抛 {@link StaleProposalException},绝不静默 rebase 到
 *       更新的状态——过期的自动执行绝不能覆盖更新的 tip。
 * 1. 校验常规的血缘/提问不变量。
 * 2. 在同一个事务内创建节点并推进路由 tip/root。
 *
 * 协作:常规自动执行经由 {@link ProposalActionExecutor} 进入;已接受的
 * 提案通过接受事务({@code REQUIRED} 传播)进入同一 bean。只读的执行族
 * (RESPOND_TO_USER / WAIT / 能力调用)从不进入本 bean,保持在图锁之外。
 */
@Service
public class AgentGraphMutationService {

    /** 事务边界内要执行的节点创建。 */
    public sealed interface NodeCreation permits InteractionNode, WorkspaceNode {
    }

    /** 一个 INTERACTION 提问节点(REQUEST_USER_INPUT / CREATE_NODE 提问)。 */
    public record InteractionNode(String questionText,
                                  String purpose,
                                  List<NodeOption> options,
                                  boolean allowFreeAnswer,
                                  boolean allowMultiSelect) implements NodeCreation {
    }

    /** 一个通用 workspace 节点(CREATE_NODE,非 INTERACTION 类型)。 */
    public record WorkspaceNode(NodeKind kind,
                                String subtype,
                                Map<String, Object> content) implements NodeCreation {
    }

    private final ProjectRepository projectRepository;
    private final RouteRepository routeRepository;
    private final NodeService nodeService;
    private final GraphInvariantValidator invariantValidator;
    private final GraphOperationRepository operationRepository;

    public AgentGraphMutationService(ProjectRepository projectRepository,
                                     RouteRepository routeRepository,
                                     NodeService nodeService,
                                     GraphInvariantValidator invariantValidator,
                                     GraphOperationRepository operationRepository) {
        this.projectRepository = projectRepository;
        this.routeRepository = routeRepository;
        this.nodeService = nodeService;
        this.invariantValidator = invariantValidator;
        this.operationRepository = operationRepository;
    }

    /**
     * 原子地应用一次 agent 节点创建。{@code expectedTipNodeId} 是模型做出
     * 决策时的锚点(决策时刻的路由 tip;空路由的根节点则为 null)。
     * 节点插入与路由 tip/root 推进一起提交。
     *
     * {@code causedBy} 会在追加的 {@link com.specagent.workspace.graph.GraphOperation}
     * 上记录提案/run 来源——agent 创建是用户可见的持久化变更,
     * 必须与用户命令进入同一个 undo 日志(actor 为 AGENT),
     * 否则 undo 栈会与真实图状态脱节。
     */
    @Transactional
    public Node executeNodeCreation(UUID projectId,
                                    UUID routeId,
                                    UUID expectedTipNodeId,
                                    NodeCreation creation) {
        return executeNodeCreation(projectId, routeId, expectedTipNodeId, creation, null);
    }

    /** 与上面的重载相同,但携带操作日志来源(例如 {@code proposal:<id>})。 */
    @Transactional
    public Node executeNodeCreation(UUID projectId,
                                    UUID routeId,
                                    UUID expectedTipNodeId,
                                    NodeCreation creation,
                                    String causedBy) {
        // 加锁顺序:project -> route/node -> graph write。
        projectRepository.lockById(projectId);
        Route route = requireOpenRouteInProject(projectId, routeId);
        verifyAnchorIsCurrentTip(route, expectedTipNodeId);

        Node node;
        if (expectedTipNodeId == null) {
            // 空路由的根节点:锚点为 null 且路由仍无 tip,
            // 因此新节点同时成为 root 和 tip。
            node = switch (creation) {
                case InteractionNode n -> nodeService.createRootNode(
                        projectId, routeId, n.questionText(), n.purpose(),
                        n.options(), n.allowFreeAnswer(), n.allowMultiSelect());
                case WorkspaceNode n -> nodeService.createWorkspaceNode(
                        projectId, routeId, null, n.kind(), n.subtype(),
                        n.content(), NodeAuthorKind.AGENT, KnowledgeStatus.PROPOSED);
            };
        } else {
            requireNodeInProject(projectId, expectedTipNodeId);
            invariantValidator.validateQuestionCanHaveChild(projectId, routeId, expectedTipNodeId);
            node = switch (creation) {
                case InteractionNode n -> nodeService.createChildNode(
                        projectId, routeId, expectedTipNodeId, n.questionText(),
                        n.purpose(), n.options(), n.allowFreeAnswer(),
                        n.allowMultiSelect());
                case WorkspaceNode n -> nodeService.createWorkspaceNode(
                        projectId, routeId, expectedTipNodeId, n.kind(), n.subtype(),
                        n.content(), NodeAuthorKind.AGENT, KnowledgeStatus.PROPOSED);
            };
        }
        operationRepository.append(projectId, GraphOperation.Actor.AGENT,
                GraphOperation.Type.CREATE_DRAFT_NODE, List.of(node.id()),
                Map.of("routeId", routeId.toString()),
                Map.of("routeId", routeId.toString(),
                       "nodeId", node.id().toString(),
                       "parentId", expectedTipNodeId == null ? "" : expectedTipNodeId.toString(),
                       "authorKind", NodeAuthorKind.AGENT.code()),
                causedBy);
        return node;
    }

    /**
     * 模型决策时的锚点必须仍然描述当前的路由:空路由要求锚点为 null,
     * 追加节点要求锚点就是活 tip。其他任何情况都意味着在排队决策期间
     * 图已经前进——fail-closed,绝不 rebase 到更新的状态。
     */
    private void verifyAnchorIsCurrentTip(Route route, UUID expectedTipNodeId) {
        if (expectedTipNodeId == null) {
            if (route.tipNodeId() != null) {
                throw new StaleProposalException(
                        "Auto-execute anchor is null but route " + route.id()
                                + " already has tip " + route.tipNodeId()
                                + "; the graph has moved on (empty-route root requires a still-empty route)");
            }
            return;
        }
        if (!expectedTipNodeId.equals(route.tipNodeId())) {
            throw new StaleProposalException(
                    "Auto-execute anchor " + expectedTipNodeId + " is no longer the route tip "
                            + route.tipNodeId() + "; the graph has moved on");
        }
    }

    private Route requireOpenRouteInProject(UUID projectId, UUID routeId) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalArgumentException("Route not found: " + routeId));
        if (!route.projectId().equals(projectId)) {
            throw new IllegalArgumentException(
                    "Route " + routeId + " does not belong to project " + projectId);
        }
        if (route.lifecycleStatus() != RouteLifecycleStatus.OPEN) {
            throw new IllegalStateException(
                    "Auto-execute target route is not open: " + route.lifecycleStatus().code());
        }
        return route;
    }

    private void requireNodeInProject(UUID projectId, UUID nodeId) {
        Node node = nodeService.getNode(nodeId)
                .orElseThrow(() -> new StaleProposalException(
                        "Auto-execute anchor node no longer exists: " + nodeId));
        if (!node.projectId().equals(projectId)) {
            throw new StaleProposalException(
                    "Auto-execute anchor node does not belong to project: " + nodeId);
        }
    }
}