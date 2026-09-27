package com.specagent.workspace.graph;

import com.specagent.common.AnswerExistencePort;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeKind;
import com.specagent.workspace.node.NodeRepository;
import com.specagent.workspace.patch.AnswerPatchRepository;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteHistoryResolver;
import com.specagent.workspace.route.RouteRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 文件名:GraphInvariantValidator.java
 *
 * 用途:写入时集中校验图不变量。领域规则集中在这里,而不是散落在
 * controller、service、Undo/Redo 和 runtime 中。任何可能推进 lineage、
 * 分支出路线、定稿答案或创建关系的变更命令,写库前都必须通过对应的
 * 校验。校验失败即拒绝(fail-closed),并抛出带稳定领域错误码
 * ({@code UNANSWERED_QUESTION_HAS_CHILD}、{@code SHARED_STATE_DIVERGENCE}、
 * {@code ROUTE_PROVENANCE_CYCLE}、{@code RELATION_DEPENDENCY_CYCLE}、
 * {@code RETRACTED_NODE_REFERENCE} 等)的 {@link IllegalStateException}
 * (状态冲突 → 409)或 {@link IllegalArgumentException}
 * (请求不合法 → 400)。
 *
 * 一个规范的 Question 节点在整个项目中只携带一个不可变的语义 Answer
 * 身份。分支路线到达该答案,要么是它自己拥有(路线局部),要么通过
 * {@code route_inherited_answers} 引用它;对共享 Question 重新作答必须
 * 创建新的 Question 节点,而不是在同一个规范节点上挂第二个 Answer。
 */
@Service
public class GraphInvariantValidator {

    private final NodeRepository nodeRepository;
    private final RouteRepository routeRepository;
    private final RouteHistoryResolver routeHistoryResolver;
    private final AnswerExistencePort answerExistencePort;
    private final AnswerPatchRepository answerPatchRepository;
    private final NodeRelationRepository relationRepository;

    public GraphInvariantValidator(NodeRepository nodeRepository,
                                   RouteRepository routeRepository,
                                   RouteHistoryResolver routeHistoryResolver,
                                   AnswerExistencePort answerExistencePort,
                                   AnswerPatchRepository answerPatchRepository,
                                   NodeRelationRepository relationRepository) {
        this.nodeRepository = nodeRepository;
        this.routeRepository = routeRepository;
        this.routeHistoryResolver = routeHistoryResolver;
        this.answerExistencePort = answerExistencePort;
        this.answerPatchRepository = answerPatchRepository;
        this.relationRepository = relationRepository;
    }

    /**
     * {@code UNANSWERED_QUESTION_HAS_CHILD}:一个 INTERACTION/QUESTION 节点
     * 在前进路线上尚无定稿的有效答案时,不允许获得 lineage 子节点。
     * 未回答的问题必须保持为路线 tip——任何推进链的命令都不得越过它。
     * 非问题节点(知识、资源、产物)永远是合法的续写点。
     *
     * 严格形态:不知道子节点的 kind,规则全量生效。
     */
    public void validateQuestionCanHaveChild(UUID projectId, UUID routeId, UUID parentNodeId) {
        validateQuestionCanHaveChild(projectId, routeId, parentNodeId, null);
    }

    /**
     * 按 kind 区分的形态,供调用方已经知道要挂什么时使用。非交互子节点
     * (资源/知识/产物)自身没有可回答的问题,把它挂到当前 tip 之下
     * 不会跳过用户仍需回答的问题——只是为已挂起的问题补充材料。
     * 如果拒绝这类挂载,tip 会变得永远挂不上东西,因为路线 tip
     * 按构造就是最新的未回答问题。
     *
     * {@code childKind == null} 表示"未知",退回严格行为,
     * 既有调用点的语义保持不变。
     */
    public void validateQuestionCanHaveChild(UUID projectId,
                                             UUID routeId,
                                             UUID parentNodeId,
                                             NodeKind childKind) {
        if (parentNodeId == null) {
            return;
        }
        if (childKind != null && childKind != NodeKind.INTERACTION) {
            return;
        }
        Node parent = nodeRepository.findById(parentNodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + parentNodeId));
        if (parent.kind() != NodeKind.INTERACTION) {
            return;
        }
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalArgumentException("Route not found: " + routeId));
        List<UUID> lineage = routeHistoryResolver.resolveLineage(route.tipNodeId());
        if (!lineage.contains(parentNodeId)) {
            // 父节点不在这条路线的 lineage 上;require-lineage 校验由
            // 调用方在自己的路径上负责。本校验只把关前进 lineage 上的
            // "越过未回答问题"。
            return;
        }
        boolean answered = routeHistoryResolver.resolveEffectiveAnswerRefs(routeId, lineage).stream()
                .anyMatch(ref -> ref.nodeId().equals(parentNodeId));
        if (!answered) {
            throw new GraphRuleViolationException("UNANSWERED_QUESTION_HAS_CHILD",
                    "UNANSWERED_QUESTION_HAS_CHILD: Question " + parentNodeId
                            + " has no finalized effective answer and cannot gain a lineage child");
        }

        // 已定稿的 Answer 若缺失 STATE_UPDATE checkpoint,必须仍能在
        // 原答案边界处恢复。不要让任何 agent 图变更把路线推过它——
        // 包括在 checkpoint 丢失、对 worker 可见之前就已入队的 run。
        for (var answer : routeHistoryResolver.resolveEffectiveAnswers(routeId, lineage)) {
            if (answerPatchRepository.findBySourceAnswerId(answer.id()).isEmpty()) {
                throw new GraphRuleViolationException(
                        "ANSWER_CYCLE_INCOMPLETE",
                        "ANSWER_CYCLE_INCOMPLETE: answer " + answer.id()
                                + " has no STATE_UPDATE checkpoint; recover it before advancing the route");
            }
        }
    }

    /**
     * {@code SHARED_STATE_DIVERGENCE}:规范节点已经携带不可变的 Answer
     * 身份(在项目的任意路线上)。同一规范节点出现第二个 Answer 会分裂
     * 共享状态;重新作答必须创建新的 Question 节点。
     */
    public void validateSharedQuestionState(UUID projectId, UUID nodeId) {
        Node node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));
        if (node.kind() != NodeKind.INTERACTION) {
            return;
        }
        if (answerExistencePort.nodeHasFinalizedAnswer(nodeId)) {
            throw new IllegalStateException(
                    "SHARED_STATE_DIVERGENCE: canonical Question " + nodeId
                            + " already has an immutable Answer identity; re-answer must create a new Question Node");
        }
    }

    /**
     * {@code ROUTE_PROVENANCE_CYCLE}:路线的 {@code sourceRouteId} 祖先链
     * 必须无环。每个新分支都会记录其来源;从任何路线沿 {@code sourceRouteId}
     * 追溯必须终止,绝不能重新经过已见过的路线。
     */
    public void validateRouteProvenance(UUID sourceRouteId) {
        Set<UUID> seen = new HashSet<>();
        UUID current = sourceRouteId;
        while (current != null) {
            if (!seen.add(current)) {
                throw new IllegalStateException(
                        "ROUTE_PROVENANCE_CYCLE: sourceRouteId ancestry cycles at route " + current);
            }
            Route route = routeRepository.findById(current).orElse(null);
            current = route == null ? null : route.sourceRouteId();
        }
    }

    /**
     * {@code INVALID_RELATION_ENDPOINT} / {@code RETRACTED_NODE_REFERENCE} /
     * {@code CROSS_PROJECT_REFERENCE}:语义关系只能连接同一项目中两个已
     * 持久化、未被撤回的规范节点;自指关系视为非法请求。
     */
    public void validateRelationEndpoints(UUID projectId, UUID sourceNodeId, UUID targetNodeId) {
        if (sourceNodeId != null && sourceNodeId.equals(targetNodeId)) {
            throw new IllegalArgumentException(
                    "INVALID_RELATION_ENDPOINT: a node cannot relate to itself");
        }
        Node source = requireNodeInProject(projectId, sourceNodeId, "source");
        Node target = requireNodeInProject(projectId, targetNodeId, "target");
        if (source.isRetracted() || target.isRetracted()) {
            throw new IllegalStateException(
                    "RETRACTED_NODE_REFERENCE: a relation cannot reference a retracted node");
        }
    }

    private Node requireNodeInProject(UUID projectId, UUID nodeId, String role) {
        Node node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "INVALID_RELATION_ENDPOINT: " + role + " node not found"));
        if (!node.projectId().equals(projectId)) {
            throw new IllegalArgumentException(
                    "CROSS_PROJECT_REFERENCE: " + role + " node belongs to another project");
        }
        return node;
    }

    /**
     * 对称关系类型的端点规范化。RELATED_TO 与 CONFLICTS_WITH 是对称的,
     * {@code A → B} 与 {@code B → A} 是同一条事实:存储的端点被规范化为
     * {@code (minId, maxId)},部分唯一索引才能对两个方向去重。有向类型
     * (DEPENDS_ON、DERIVED_FROM、SUPPORTS)保留创建时的方向。
     */
    public static CanonicalEndpoints endpointsCanonicalized(UUID sourceNodeId, UUID targetNodeId, NodeRelationType type) {
        boolean symmetric = type == NodeRelationType.RELATED_TO || type == NodeRelationType.CONFLICTS_WITH;
        if (!symmetric || sourceNodeId.compareTo(targetNodeId) <= 0) {
            return new CanonicalEndpoints(sourceNodeId, targetNodeId);
        }
        return new CanonicalEndpoints(targetNodeId, sourceNodeId);
    }

    public record CanonicalEndpoints(UUID sourceNodeId, UUID targetNodeId) {
    }

    /**
     * 关系创建的三道闸:端点合法性、对称重复检测(调用方已做端点规范化),
     * 以及 {@code DEPENDS_ON} / {@code DERIVED_FROM} 的因果/出处 DAG。
     * {@code SUPPORTS} 有意不加入该 DAG。
     */
    public void validateRelationCreation(UUID projectId,
                                         UUID sourceNodeId,
                                         UUID targetNodeId,
                                         NodeRelationType type) {
        validateRelationEndpoints(projectId, sourceNodeId, targetNodeId);
        if (type == NodeRelationType.DEPENDS_ON || type == NodeRelationType.DERIVED_FROM) {
            validateDependencyCycle(projectId, sourceNodeId, targetNodeId, type);
        }
    }

    /**
     * {@code RELATION_DEPENDENCY_CYCLE}:DEPENDS_ON 与 DERIVED_FROM 联合
     * 构成因果/出处依赖 DAG。加入 {@code source → target} 不得产生环——
     * 即 {@code target} 不得已经通过任意一条激活的 DEPENDS_ON /
     * DERIVED_FROM 边链到达 {@code source}。该校验在后端命令层执行;
     * 前端只做预提示。
     */
    public void validateDependencyCycle(UUID projectId,
                                        UUID sourceNodeId,
                                        UUID targetNodeId,
                                        NodeRelationType addedType) {
        Map<UUID, List<UUID>> adjacency = new HashMap<>();
        for (NodeRelation relation : relationRepository.findActiveByProject(projectId)) {
            NodeRelationType type = relation.relationType();
            if (type != NodeRelationType.DEPENDS_ON && type != NodeRelationType.DERIVED_FROM) {
                continue;
            }
            adjacency.computeIfAbsent(relation.sourceNodeId(), k -> new java.util.ArrayList<>())
                    .add(relation.targetNodeId());
        }
        if (reaches(adjacency, targetNodeId, sourceNodeId)) {
            throw new GraphRuleViolationException("RELATION_DEPENDENCY_CYCLE",
                    "RELATION_DEPENDENCY_CYCLE: " + addedType.code()
                            + " would create a causal/provenance cycle between "
                            + sourceNodeId + " and " + targetNodeId);
        }
    }

    private boolean reaches(Map<UUID, List<UUID>> adjacency, UUID from, UUID target) {
        Deque<UUID> stack = new ArrayDeque<>();
        Set<UUID> visited = new HashSet<>();
        stack.push(from);
        while (!stack.isEmpty()) {
            UUID current = stack.pop();
            if (current.equals(target)) {
                return true;
            }
            if (!visited.add(current)) {
                continue;
            }
            for (UUID next : adjacency.getOrDefault(current, List.of())) {
                stack.push(next);
            }
        }
        return false;
    }
}
