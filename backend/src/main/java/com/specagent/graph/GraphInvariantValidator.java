package com.specagent.graph;

import com.specagent.answer.AnswerRepository;
import com.specagent.node.Node;
import com.specagent.node.NodeKind;
import com.specagent.node.NodeRepository;
import com.specagent.route.Route;
import com.specagent.route.RouteHistoryResolver;
import com.specagent.route.RouteRepository;
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
 * Centralized write-time graph invariant validation.
 *
 * <p>Domain rules live here instead of being scattered across controllers,
 * services, Undo/Redo, and the runtime. Every mutation command that can
 * advance lineage, branch routes, finalize answers, or create relations must
 * pass the matching validation before writing. Validators fail closed with
 * stable domain error codes ({@code UNANSWERED_QUESTION_HAS_CHILD},
 * {@code SHARED_STATE_DIVERGENCE}, {@code ROUTE_PROVENANCE_CYCLE},
 * {@code RELATION_DEPENDENCY_CYCLE}, {@code RETRACTED_NODE_REFERENCE}, ...)
 * as {@link IllegalStateException} (state conflict → 409) or
 * {@link IllegalArgumentException} (malformed request → 400).
 *
 * <p>A canonical Question Node carries exactly one immutable semantic Answer
 * identity project-wide. Route branches arrive at that answer either because
 * they own it (route-local) or because they reference it through
 * {@code route_inherited_answers}; re-answering a shared Question must create
 * a new Question Node instead of a second Answer on the same canonical node.
 */
@Service
public class GraphInvariantValidator {

    private final NodeRepository nodeRepository;
    private final RouteRepository routeRepository;
    private final RouteHistoryResolver routeHistoryResolver;
    private final AnswerRepository answerRepository;
    private final NodeRelationRepository relationRepository;

    public GraphInvariantValidator(NodeRepository nodeRepository,
                                   RouteRepository routeRepository,
                                   RouteHistoryResolver routeHistoryResolver,
                                   AnswerRepository answerRepository,
                                   NodeRelationRepository relationRepository) {
        this.nodeRepository = nodeRepository;
        this.routeRepository = routeRepository;
        this.routeHistoryResolver = routeHistoryResolver;
        this.answerRepository = answerRepository;
        this.relationRepository = relationRepository;
    }

    /**
     * {@code UNANSWERED_QUESTION_HAS_CHILD}: an INTERACTION/QUESTION node
     * without a finalized effective answer on the advancing route may not
     * gain a lineage child. Unanswered Questions must stay route tips — no
     * line advancing command may cross them. Non-question parents (knowledge,
     * resource, artifact) are always valid continuation points.
     */
    public void validateQuestionCanHaveChild(UUID projectId, UUID routeId, UUID parentNodeId) {
        if (parentNodeId == null) {
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
            // The parent is not on this route's lineage; the caller already
            // owns the require-lineage check for its own paths. This validator
            // only guards unanswered crossing on the advancing lineage.
            return;
        }
        boolean answered = routeHistoryResolver.resolveEffectiveAnswers(routeId, lineage).stream()
                .anyMatch(answer -> answer.nodeId().equals(parentNodeId));
        if (!answered) {
            throw new IllegalStateException(
                    "UNANSWERED_QUESTION_HAS_CHILD: Question " + parentNodeId
                            + " has no finalized effective answer and cannot gain a lineage child");
        }
    }

    /**
     * {@code SHARED_STATE_DIVERGENCE}: the canonical node already carries an
     * immutable Answer identity (on any route of the project). A second Answer
     * for the same canonical node would split shared state; re-answering must
     * create a new Question Node instead.
     */
    public void validateSharedQuestionState(UUID projectId, UUID nodeId) {
        Node node = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalArgumentException("Node not found: " + nodeId));
        if (node.kind() != NodeKind.INTERACTION) {
            return;
        }
        if (answerRepository.existsByNodeId(nodeId)) {
            throw new IllegalStateException(
                    "SHARED_STATE_DIVERGENCE: canonical Question " + nodeId
                            + " already has an immutable Answer identity; re-answer must create a new Question Node");
        }
    }

    /**
     * {@code ROUTE_PROVENANCE_CYCLE}: the {@code sourceRouteId} ancestry of a
     * route must be acyclic. Every new branch records its source; following
     * {@code sourceRouteId} from any route must terminate, never revisit a
     * route already seen.
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
     * {@code CROSS_PROJECT_REFERENCE}: a semantic relation may only connect
     * two persisted, non-retracted canonical nodes of the same project; self
     * relations are malformed.
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
     * Canonical endpoint order for symmetric relation types. RELATED_TO and
     * CONFLICTS_WITH are symmetric, so {@code A → B} and {@code B → A} are the
     * same fact: the stored endpoints are normalized to {@code (minId, maxId)}
     * so the partial unique index deduplicates both directions. Directional
     * types (DEPENDS_ON, DERIVED_FROM, SUPPORTS) keep the authored direction.
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
     * Relation creation gates: endpoint validity, symmetric duplicate
     * detection (already canonicalized by the caller), and the
     * {@code DEPENDS_ON} / {@code DERIVED_FROM} causal-provenance DAG.
     * {@code SUPPORTS} intentionally does NOT join the DAG.
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
     * {@code RELATION_DEPENDENCY_CYCLE}: DEPENDS_ON and DERIVED_FROM jointly
     * form the causal/provenance dependency DAG. Adding {@code source → target}
     * must not create a cycle — i.e. {@code target} must not already reach
     * {@code source} through any chain of active DEPENDS_ON / DERIVED_FROM
     * edges. The check runs at the backend command layer; the frontend only
     * pre-hints.
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
            throw new IllegalStateException(
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