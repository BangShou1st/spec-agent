package com.specagent.workspace.route;

import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerRepository;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeKind;
import com.specagent.workspace.node.NodeRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 文件名:RouteHistoryResolver.java
 *
 * 用途:解析一条路线生效的不可变答案历史。分支路线持有对来源
 * Answer 的冻结引用;本服务是唯一把"继承的来源引用"与"路线本地的
 * Answer"合并起来的地方。
 */
@Service
public class RouteHistoryResolver {

    private static final int MAX_LINEAGE_DEPTH = 10_000;

    private final RouteRepository routeRepository;
    private final NodeRepository nodeRepository;
    private final AnswerRepository answerRepository;
    private final RouteInheritedAnswerRepository inheritedAnswerRepository;

    public RouteHistoryResolver(RouteRepository routeRepository,
                                NodeRepository nodeRepository,
                                AnswerRepository answerRepository,
                                RouteInheritedAnswerRepository inheritedAnswerRepository) {
        this.routeRepository = routeRepository;
        this.nodeRepository = nodeRepository;
        this.answerRepository = answerRepository;
        this.inheritedAnswerRepository = inheritedAnswerRepository;
    }

    /**
     * 冻结穿过分支点的、生效的来源路线答案引用。来源 Answer/Patch 行
     * 仍归原路线所有。
     */
    public List<RouteInheritedAnswer> snapshotInheritedPrefix(UUID newRouteId,
                                                               UUID sourceRouteId,
                                                               UUID throughNodeId,
                                                               boolean includeThroughNode) {
        Route sourceRoute = routeRepository.findById(sourceRouteId)
                .orElseThrow(() -> new IllegalArgumentException("Source route not found: " + sourceRouteId));
        List<UUID> lineage = resolveLineage(throughNodeId);
        // 分支点必须属于本路线:tip 谱系上的节点,或挂在谱系下的派生
        // 知识/资源(它们自身就是一条到根的完整父链,如 "派生知识分叉")。
        if (!belongsToRoute(sourceRoute, throughNodeId)) {
            throw new IllegalArgumentException("Branch node is not on source route: " + throughNodeId);
        }
        if (!includeThroughNode && !lineage.isEmpty()) {
            lineage = lineage.subList(0, lineage.size() - 1);
        }

        Map<UUID, Answer> effective = new HashMap<>();
        for (Answer answer : resolveEffectiveAnswers(sourceRouteId, resolveLineage(sourceRoute.tipNodeId()))) {
            effective.put(answer.nodeId(), answer);
        }
        List<RouteInheritedAnswer> references = new ArrayList<>();
        int ordinal = 0;
        for (UUID nodeId : lineage) {
            Answer answer = effective.get(nodeId);
            if (answer != null) {
                references.add(new RouteInheritedAnswer(
                        newRouteId, ordinal++, nodeId, answer.id(), answer.routeId()));
            }
        }
        inheritedAnswerRepository.saveAll(references);
        return List.copyOf(references);
    }

    /** 按规范的根到尾节点顺序返回生效的答案记录。 */
    public List<Answer> resolveEffectiveAnswers(UUID routeId, List<UUID> lineageNodeIds) {
        Map<UUID, Answer> byNode = new HashMap<>();
        for (RouteInheritedAnswer reference : inheritedAnswerRepository.findByBranchRouteId(routeId)) {
            answerRepository.findById(reference.answerId()).ifPresent(answer -> byNode.put(reference.nodeId(), answer));
        }
        for (Answer answer : answerRepository.findByRouteAndNodeIds(routeId, lineageNodeIds)) {
            byNode.put(answer.nodeId(), answer);
        }
        List<Answer> resolved = new ArrayList<>();
        for (UUID nodeId : lineageNodeIds) {
            Answer answer = byNode.get(nodeId);
            if (answer != null) {
                resolved.add(answer);
            }
        }
        return List.copyOf(resolved);
    }

    /** 按根到尾顺序返回生效的不可变 Answer 引用。 */
    public List<RouteInheritedAnswer> resolveEffectiveAnswerRefs(UUID routeId, List<UUID> lineageNodeIds) {
        Map<UUID, RouteInheritedAnswer> byNode = new HashMap<>();
        for (RouteInheritedAnswer ref : inheritedAnswerRepository.findByBranchRouteId(routeId)) {
            byNode.put(ref.nodeId(), ref);
        }
        for (Answer answer : answerRepository.findByRouteAndNodeIds(routeId, lineageNodeIds)) {
            byNode.put(answer.nodeId(), new RouteInheritedAnswer(
                    routeId, Integer.MAX_VALUE, answer.nodeId(), answer.id(), routeId));
        }
        List<RouteInheritedAnswer> resolved = new ArrayList<>();
        int ordinal = 0;
        for (UUID nodeId : lineageNodeIds) {
            RouteInheritedAnswer ref = byNode.get(nodeId);
            if (ref != null) {
                resolved.add(new RouteInheritedAnswer(
                        routeId, ordinal++, ref.nodeId(), ref.answerId(), ref.ownerRouteId()));
            }
        }
        return List.copyOf(resolved);
    }

    public List<UUID> resolveLineage(UUID tipNodeId) {
        List<UUID> reverse = new ArrayList<>();
        Set<UUID> seen = new HashSet<>();
        UUID current = tipNodeId;
        while (current != null) {
            if (!seen.add(current)) {
                throw new IllegalStateException("Node lineage contains a cycle");
            }
            reverse.add(current);
            UUID nodeId = current;
            Node node = nodeRepository.findById(nodeId)
                    .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));
            current = node.parentNodeId();
            if (reverse.size() > MAX_LINEAGE_DEPTH) {
                throw new IllegalStateException("Node lineage exceeds maximum depth");
            }
        }
        java.util.Collections.reverse(reverse);
        return List.copyOf(reverse);
    }

    /**
     * 判断 {@code nodeId} 是否属于该路线的资产:它要么本身位于 tip 谱系上,
     * 要么是挂在谱系节点下的 KNOWLEDGE/RESOURCE 派生物。分叉点之后的
     * interaction 子节点归属于"其 tip 包含该子节点的路线";不能仅因父节点
     * 被共享就泄漏到兄弟路线。游离节点(detached/floating)一律判否。
     */
    public boolean belongsToRoute(Route route, UUID nodeId) {
        if (nodeId == null || route.tipNodeId() == null) {
            return false;
        }
        Set<UUID> lineage = new HashSet<>(resolveLineage(route.tipNodeId()));
        UUID current = nodeId;
        Set<UUID> seen = new HashSet<>();
        while (current != null && seen.add(current)) {
            if (lineage.contains(current)) {
                return true;
            }
            Node node = nodeRepository.findById(current)
                    .orElse(null);
            if (node == null) {
                return false;
            }
            if (node.kind() == NodeKind.INTERACTION) {
                return false;
            }
            current = node.parentNodeId();
        }
        return false;
    }

    private boolean containsNode(List<UUID> lineage, UUID nodeId) {
        return lineage.contains(nodeId);
    }
}
