package com.specagent.route;

import com.specagent.answer.Answer;
import com.specagent.answer.AnswerRepository;
import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Resolves the effective immutable answer history of a route. Branch routes
 * carry frozen references to source Answers; this service is the only place
 * that combines those references with route-local Answers.
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
     * Freezes effective source-route answer references through a branch point.
     * The source Answer/Patch rows remain owned by their original route.
     */
    public List<RouteInheritedAnswer> snapshotInheritedPrefix(UUID newRouteId,
                                                               UUID sourceRouteId,
                                                               UUID throughNodeId,
                                                               boolean includeThroughNode) {
        Route sourceRoute = routeRepository.findById(sourceRouteId)
                .orElseThrow(() -> new IllegalArgumentException("Source route not found: " + sourceRouteId));
        List<UUID> lineage = resolveLineage(throughNodeId);
        if (!containsNode(resolveLineage(sourceRoute.tipNodeId()), throughNodeId)) {
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

    /** Effective answer records in canonical root-to-tip node order. */
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

    /** Effective immutable Answer references in root-to-tip order. */
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

    private boolean containsNode(List<UUID> lineage, UUID nodeId) {
        return lineage.contains(nodeId);
    }
}
