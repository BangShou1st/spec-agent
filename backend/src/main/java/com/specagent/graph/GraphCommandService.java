package com.specagent.graph;

import com.specagent.answer.AnswerRepository;
import com.specagent.node.KnowledgeStatus;
import com.specagent.node.Node;
import com.specagent.node.NodeAuthorKind;
import com.specagent.node.NodeKind;
import com.specagent.node.NodeRepository;
import com.specagent.node.NodeService;
import com.specagent.route.Route;
import com.specagent.route.RouteBranchType;
import com.specagent.route.RouteHistoryResolver;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteRepository;
import com.specagent.route.RouteService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Transactional graph mutation commands for the graph workspace model.
 *
 * <p>Every user-visible durable mutation goes through one command method that
 * (1) validates against graph invariants, (2) performs the mutation, and
 * (3) appends a typed {@link GraphOperation} in the same transaction. The
 * external agent action protocol stays generic: {@code CREATE_NODE} /
 * {@code CONNECT_NODE} map onto these commands after policy approval; no
 * business-specific command names exist here.
 *
 * <p>Invariants enforced: no historical insertion (a continuation from a
 * non-tip node always creates an explicit branch route), immutable answers are
 * never mutated or deleted, shared node identity is never cloned, and route
 * ambiguity is never resolved by falling back to active/first/latest route.
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
    private final AnswerRepository answerRepository;

    public GraphCommandService(NodeService nodeService,
                               NodeRepository nodeRepository,
                               RouteService routeService,
                               RouteRepository routeRepository,
                               RouteHistoryResolver routeHistoryResolver,
                               NodeRelationRepository relationRepository,
                               GraphOperationRepository operationRepository,
                               AnswerRepository answerRepository) {
        this.nodeService = nodeService;
        this.nodeRepository = nodeRepository;
        this.routeService = routeService;
        this.routeRepository = routeRepository;
        this.routeHistoryResolver = routeHistoryResolver;
        this.relationRepository = relationRepository;
        this.operationRepository = operationRepository;
        this.answerRepository = answerRepository;
    }

    /**
     * Creates the first (root) draft node on an empty route. This is the
     * zero-model-call entry into a fresh project: the user authors content
     * before any agent involvement.
     */
    @Transactional
    public Node createRootDraftNode(UUID projectId,
                                    UUID routeId,
                                    String subtype,
                                    Map<String, Object> content) {
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
     * Creates a standalone (floating) user draft on an explicit route. The
     * node starts disconnected from every lineage — the route tip is never
     * advanced — so "+ idea" never silently rewires the graph. The route id
     * is kept in the operation log only as creation context. Undo/redo treat
     * the {@code floating} ref as "no tip/root side effects".
     */
    @Transactional
    public Node createFloatingDraftNode(UUID projectId,
                                        UUID routeId,
                                        String subtype,
                                        Map<String, Object> content) {
        requireOpenRouteInProject(projectId, routeId);
        Node node = nodeService.createFloatingWorkspaceNode(
                projectId, routeId, NodeKind.KNOWLEDGE, subtype, content,
                NodeAuthorKind.USER, KnowledgeStatus.PROPOSED);
        operationRepository.append(projectId, GraphOperation.Actor.USER,
                GraphOperation.Type.CREATE_DRAFT_NODE, List.of(node.id()),
                Map.of("routeId", routeId.toString()),
                Map.of("routeId", routeId.toString(),
                       "nodeId", node.id().toString(),
                       "subtype", node.subtype(),
                       "floating", true));
        return node;
    }

    /** Result of a continuation command: the new node plus the route it landed on. */
    public record ContinuationResult(Node node, Route route, boolean branched) {
    }

    /**
     * Continues exploration from any node on an explicit route.
     *
     * <p>If the source is the route tip, the new node is appended and the tip
     * advances. If the source is a historical (non-tip) node, an explicit
     * branch route is created from that point — historical lineage is never
     * rewritten and nothing is inserted between existing nodes. The effective
     * answer prefix through the branch point is frozen as immutable inherited
     * references, exactly like a fork.
     */
    @Transactional
    public ContinuationResult appendContinuation(UUID projectId,
                                                 UUID routeId,
                                                 UUID sourceNodeId,
                                                 String subtype,
                                                 Map<String, Object> content) {
        Route route = requireOpenRouteInProject(projectId, routeId);
        Node sourceNode = requireNodeInProject(projectId, sourceNodeId);
        requireLineageContains(route, sourceNodeId);

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

        // Non-tip source: create an explicit branch route; never insert into history.
        Instant now = Instant.now();
        UUID branchRouteId = com.specagent.common.Ids.random();
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

        // The user is now working on the branch; make it the active route.
        routeService.setActiveRoute(projectId, branchRouteId);
        return new ContinuationResult(node, routeRepository.findById(branchRouteId).orElse(branchRoute), true);
    }

    /**
     * Attaches a resource node (FILE/URL/TEXT/...) authored by the user.
     *
     * <p>A resource may become the root of an empty route or be appended at
     * the current tip — it never branches from a historical node and never
     * carries knowledge-state semantics. Resources are context sources for
     * capabilities (bounded excerpts with provenance), not confirmed claims.
     */
    @Transactional
    public Node attachResource(UUID projectId,
                               UUID routeId,
                               UUID parentNodeId,
                               String subtype,
                               Map<String, Object> content) {
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

    /** Edits a still-editable user draft in place, logging the prior state. */
    @Transactional
    public Node reviseDraftNode(UUID projectId, UUID nodeId, String subtype, Map<String, Object> content) {
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
     * Creates a semantic relation. Origin records provenance: USER relations
     * are explicit user operations; AGENT relations arrive here only after an
     * accepted Advisor proposal ({@code createdByProposalId}).
     */
    @Transactional
    public NodeRelation createSemanticRelation(UUID projectId,
                                               UUID sourceNodeId,
                                               UUID targetNodeId,
                                               NodeRelationType type,
                                               NodeRelation.Origin origin,
                                               UUID createdByProposalId,
                                               UUID createdByRunId) {
        requireNodeInProject(projectId, sourceNodeId);
        requireNodeInProject(projectId, targetNodeId);
        NodeRelation relation = relationRepository.insertActiveOrThrowDuplicate(
                projectId, sourceNodeId, targetNodeId, type, origin, createdByProposalId, createdByRunId);
        operationRepository.append(projectId,
                origin == NodeRelation.Origin.USER ? GraphOperation.Actor.USER : GraphOperation.Actor.AGENT,
                GraphOperation.Type.CREATE_SEMANTIC_RELATION, List.of(relation.id()),
                Map.of(),
                Map.of("relationId", relation.id().toString(),
                       "sourceNodeId", sourceNodeId.toString(),
                       "targetNodeId", targetNodeId.toString(),
                       "relationType", type.code()));
        return relation;
    }

    /** Applies an explicit knowledge-state transition (e.g. PROPOSED -> CONFIRMED). */
    @Transactional
    public Node setKnowledgeStatus(UUID projectId, UUID nodeId, KnowledgeStatus status) {
        Node before = requireNodeInProject(projectId, nodeId);
        Node after = nodeService.setKnowledgeStatus(projectId, nodeId, status);
        operationRepository.append(projectId, GraphOperation.Actor.USER,
                GraphOperation.Type.SET_KNOWLEDGE_STATUS, List.of(nodeId),
                Map.of("status", before.knowledgeStatus().code()),
                Map.of("status", after.knowledgeStatus().code()));
        return after;
    }

    /** Lists the typed operation log (for UI undo/redo affordances and audits). */
    public List<GraphOperation> listOperations(UUID projectId) {
        return operationRepository.findByProject(projectId);
    }

    /** Lists active semantic relations of the project. */
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

    private String nextBranchLabel(UUID projectId, String prefix) {
        long count = routeRepository.findByProject(projectId).stream()
                .filter(route -> route.branchType() == RouteBranchType.CONTINUATION)
                .count();
        return prefix + " " + (count + 1);
    }
}
