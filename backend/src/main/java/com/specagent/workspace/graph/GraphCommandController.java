package com.specagent.workspace.graph;

import com.specagent.workspace.graph.GraphCommandService;
import com.specagent.workspace.graph.GraphOperation;
import com.specagent.workspace.graph.NodeRelation;
import com.specagent.workspace.graph.NodeRelationType;
import com.specagent.workspace.graph.UndoRedoService;
import com.specagent.workspace.node.KnowledgeStatus;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeKind;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.graph.GraphWorkspaceRelationView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Graph workspace mutation API.
 *
 * <p>Every endpoint is a transactional Runtime command (see
 * {@link GraphCommandService}); none of them call a model. Route context is
 * always explicit — the API never resolves shared-node ambiguity by falling
 * back to an active/first/latest route. Undo/Redo is operation-specific
 * compensation over the typed operation log, never a destructive rewrite.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class GraphCommandController {

    private final GraphCommandService commandService;
    private final UndoRedoService undoRedoService;
    private final NodeService nodeService;

    public GraphCommandController(GraphCommandService commandService,
                                  UndoRedoService undoRedoService,
                                  NodeService nodeService) {
        this.commandService = commandService;
        this.undoRedoService = undoRedoService;
        this.nodeService = nodeService;
    }

    /** Creates the first (root) draft node on an empty route. Zero model calls. */
    @PostMapping("/nodes")
    public ResponseEntity<NodeResponse> createRootDraftNode(@PathVariable UUID projectId,
                                                             @RequestBody CreateDraftNodeRequest request) {
        Node node = com.specagent.workspace.route.CommandExecution.execute(() -> commandService.createRootDraftNode(
                projectId, request.routeId(), request.subtype(), request.content()));
        return ResponseEntity.status(HttpStatus.CREATED).body(NodeResponse.from(node, request.routeId(), false));
    }

    /**
     * Creates a standalone (floating) draft that starts disconnected from
     * every lineage; the user connects it manually on the canvas. The
     * response shape uses {@code routeId = null} so the client never sees
     * the floating node as belonging to any route. The creation context
     * route id is recorded in the operation log by the command service.
     *
     * <p>{@code kind} is optional and defaults to KNOWLEDGE; resources
     * ({@code kind = RESOURCE}) start floating the same way, which is what
     * makes "先添加资源、再自己连线接入路线" possible.
     */
    @PostMapping("/floating-nodes")
    public ResponseEntity<NodeResponse> createFloatingDraftNode(@PathVariable UUID projectId,
                                                                 @RequestBody CreateDraftNodeRequest request) {
        Node node = com.specagent.workspace.route.CommandExecution.execute(() -> commandService.createFloatingDraftNode(
                projectId, request.routeId(), request.resolveNodeKind(), request.subtype(), request.content()));
        return ResponseEntity.status(HttpStatus.CREATED).body(NodeResponse.fromFloating(node));
    }

    /**
     * Connects an existing floating node into a route as its new tip — the
     * "自己连线" half of resource attachment. Only the current tip may take a
     * new lineage child, so a hand-drawn connection can never insert into
     * history. The node's id, kind and content are untouched.
     */
    @PostMapping("/nodes/{nodeId}/connect")
    public ResponseEntity<NodeResponse> connectFloatingNode(@PathVariable UUID projectId,
                                                            @PathVariable UUID nodeId,
                                                            @RequestBody ConnectNodeRequest request) {
        Node node = com.specagent.workspace.route.CommandExecution.execute(
                () -> commandService.connectFloatingNodeToRoute(
                        projectId, request.routeId(), nodeId, request.parentNodeId()));
        return ResponseEntity.ok(NodeResponse.from(node, request.routeId(), false));
    }

    /** Detaches the current tip from its route, restoring a floating node. */
    @PostMapping("/nodes/{nodeId}/disconnect")
    public ResponseEntity<NodeResponse> disconnectNode(@PathVariable UUID projectId,
                                                       @PathVariable UUID nodeId,
                                                       @RequestBody DisconnectNodeRequest request) {
        Node node = com.specagent.workspace.route.CommandExecution.execute(
                () -> commandService.detachNodeFromRoute(projectId, request.routeId(), nodeId));
        return ResponseEntity.ok(NodeResponse.fromFloating(node));
    }

    /**
     * Continues from any node on an explicit route. Appending at the tip
     * advances the route; continuing from a historical node creates an
     * explicit branch route (never a historical insertion).
     */
    @PostMapping("/nodes/{nodeId}/continuation")
    public ResponseEntity<NodeResponse> appendContinuation(@PathVariable UUID projectId,
                                                           @PathVariable UUID nodeId,
                                                           @RequestBody CreateDraftNodeRequest request) {
        GraphCommandService.ContinuationResult result = com.specagent.workspace.route.CommandExecution.execute(
                () -> commandService.appendContinuation(
                        projectId, request.routeId(), nodeId, request.subtype(), request.content()));
        return ResponseEntity.status(HttpStatus.CREATED).body(
                NodeResponse.from(result.node(), result.route().id(), result.branched()));
    }

    /** Edits a still-editable user draft in place (subtype/content only). */
    @PatchMapping("/nodes/{nodeId}/draft")
    public NodeResponse reviseDraft(@PathVariable UUID projectId,
                                    @PathVariable UUID nodeId,
                                    @RequestBody ReviseDraftRequest request) {
        Node node = commandService.reviseDraftNode(projectId, nodeId, request.subtype(), request.content());
        return NodeResponse.from(node, null, false);
    }

    /** Explicit knowledge-state transition for claim-like node content. */
    @PostMapping("/nodes/{nodeId}/knowledge-status")
    public NodeResponse setKnowledgeStatus(@PathVariable UUID projectId,
                                           @PathVariable UUID nodeId,
                                           @RequestBody KnowledgeStatusRequest request) {
        Node node = commandService.setKnowledgeStatus(
                projectId, nodeId, KnowledgeStatus.fromCode(request.status()));
        return NodeResponse.from(node, null, false);
    }

    /**
     * Attaches a user-authored resource node (empty-route root or current
     * tip append). Resources are capability context sources, never confirmed
     * claims. Zero model calls.
     */
    @PostMapping("/resources")
    public ResponseEntity<NodeResponse> attachResource(@PathVariable UUID projectId,
                                                       @RequestBody AttachResourceRequest request) {
        Node node = com.specagent.workspace.route.CommandExecution.execute(() -> commandService.attachResource(
                projectId, request.routeId(), request.parentNodeId(),
                request.subtype(), request.content()));
        return ResponseEntity.status(HttpStatus.CREATED).body(
                NodeResponse.from(node, request.routeId(), false));
    }

    /** Lists active semantic relations (Inspector data, not Canvas edges). */
    @GetMapping("/relations")
    public List<GraphWorkspaceRelationView> listRelations(@PathVariable UUID projectId) {
        return commandService.listRelations(projectId).stream()
                .map(GraphWorkspaceRelationView::from)
                .toList();
    }

    /** Creates an explicit user-authored semantic relation. */
    @PostMapping("/relations")
    public ResponseEntity<GraphWorkspaceRelationView> createRelation(@PathVariable UUID projectId,
                                                                     @RequestBody CreateRelationRequest request) {
        NodeRelation relation = com.specagent.workspace.route.CommandExecution.execute(() -> commandService.createSemanticRelation(
                projectId,
                request.sourceNodeId(),
                request.targetNodeId(),
                NodeRelationType.fromCode(request.relationType()),
                NodeRelation.Origin.USER,
                null,
                null));
        return ResponseEntity.status(HttpStatus.CREATED).body(GraphWorkspaceRelationView.from(relation));
    }

    /** Typed operation log for audits and undo/redo affordances. */
    @GetMapping("/graph-operations")
    public List<GraphOperationResponse> listOperations(@PathVariable UUID projectId) {
        return commandService.listOperations(projectId).stream()
                .map(GraphOperationResponse::from)
                .toList();
    }

    @GetMapping("/graph-operations/availability")
    public Map<String, Boolean> undoRedoAvailability(@PathVariable UUID projectId) {
        return Map.of(
                "canUndo", undoRedoService.canUndo(projectId),
                "canRedo", undoRedoService.canRedo(projectId));
    }

    @PostMapping("/graph-operations/undo")
    public Map<String, Object> undo(@PathVariable UUID projectId) {
        UndoRedoService.UndoRedoResult result = com.specagent.workspace.route.CommandExecution.execute(
                () -> undoRedoService.undo(projectId));
        return undoRedoBody(result);
    }

    @PostMapping("/graph-operations/redo")
    public Map<String, Object> redo(@PathVariable UUID projectId) {
        UndoRedoService.UndoRedoResult result = com.specagent.workspace.route.CommandExecution.execute(
                () -> undoRedoService.redo(projectId));
        return undoRedoBody(result);
    }

    /**
     * Undo/redo response. {@code targetTitle} names the compensated node so the
     * client can say WHICH node was undone ("已撤销「…」") instead of only the
     * operation type — an undo can compensate a node the agent just produced,
     * which the user cannot otherwise recognize. The title is read here at the
     * API edge from the operation's primary target; the compensation logic in
     * {@link UndoRedoService} stays untouched.
     */
    private Map<String, Object> undoRedoBody(UndoRedoService.UndoRedoResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operation", GraphOperationResponse.from(result.operation()));
        body.put("description", result.description());
        body.put("targetTitle", targetTitle(result.operation()));
        return body;
    }

    /** Display title of the operation's primary target node, or null. */
    private String targetTitle(GraphOperation operation) {
        UUID nodeId = operation.targetNodeId();
        if (nodeId == null) {
            return null;
        }
        return nodeService.getNode(nodeId).map(GraphCommandController::displayTitle).orElse(null);
    }

    /**
     * A question node is titled by its question; every other node by its
     * primary text payload (what the user actually wrote/attached).
     */
    private static String displayTitle(Node node) {
        if (node.kind() == NodeKind.INTERACTION) {
            String question = node.question();
            return question == null || question.isBlank() ? null : question;
        }
        String text = node.contentText();
        return text != null ? text : (node.question() == null || node.question().isBlank()
                ? null : node.question());
    }

    // ------------------------------------------------------------------
    // Request/response records
    // ------------------------------------------------------------------

    /**
     * JSON-safe projection of a {@link GraphOperation}. The domain class
     * exposes record-style accessors ({@code id()}, {@code type()}, ...),
     * which Jackson's default bean detection cannot see, so serializing the
     * domain object directly failed with "no properties discovered" — the
     * undo/redo transaction committed and only then the response 500-ed.
     */
    public record GraphOperationResponse(UUID id,
                                         UUID projectId,
                                         String actor,
                                         String type,
                                         List<UUID> targets,
                                         Map<String, Object> beforeRefs,
                                         Map<String, Object> afterRefs,
                                         String causedBy,
                                         boolean reversible,
                                         String status,
                                         Instant createdAt,
                                         Instant undoneAt) {

        static GraphOperationResponse from(GraphOperation operation) {
            return new GraphOperationResponse(
                    operation.id(),
                    operation.projectId(),
                    operation.actor().name(),
                    operation.type().name(),
                    operation.targets(),
                    operation.beforeRefs(),
                    operation.afterRefs(),
                    operation.causedBy(),
                    operation.reversible(),
                    operation.status().name(),
                    operation.createdAt(),
                    operation.undoneAt());
        }
    }

    /**
     * Draft-node creation payload.
     *
     * <p>{@code nodeKind} is OPTIONAL and only honoured by the floating-node
     * endpoint (default KNOWLEDGE): /nodes and /nodes/{id}/continuation keep
     * their fixed kinds so an existing flow can never be turned into a
     * resource by a stray field.
     */
    public record CreateDraftNodeRequest(UUID routeId,
                                         String subtype,
                                         Map<String, Object> content,
                                         String nodeKind) {

        NodeKind resolveNodeKind() {
            return nodeKind == null || nodeKind.isBlank()
                    ? NodeKind.KNOWLEDGE
                    : NodeKind.fromCode(nodeKind);
        }
    }

    public record ConnectNodeRequest(UUID routeId, UUID parentNodeId) {
    }

    public record DisconnectNodeRequest(UUID routeId) {
    }

    public record ReviseDraftRequest(String subtype, Map<String, Object> content) {
    }

    public record KnowledgeStatusRequest(String status) {
    }

    public record CreateRelationRequest(UUID sourceNodeId, UUID targetNodeId, String relationType) {
    }

    public record AttachResourceRequest(UUID routeId,
                                        UUID parentNodeId,
                                        String subtype,
                                        Map<String, Object> content) {
    }

    public record NodeResponse(UUID id,
                               UUID routeId,
                               boolean branched,
                               String kind,
                               String subtype,
                               Map<String, Object> content,
                               String authorKind,
                               String knowledgeStatus) {

        static NodeResponse from(Node node, UUID routeId, boolean branched) {
            return new NodeResponse(
                    node.id(), routeId, branched,
                    node.kind().code(), node.subtype(), node.content(),
                    node.authorKind().code(),
                    node.knowledgeStatus() == null ? null : node.knowledgeStatus().code());
        }

        /**
         * Floating (route-less) response: routeId is always null. The creation
         * context route id is recorded in the operation log; the response
         * shape itself never claims route membership.
         */
        static NodeResponse fromFloating(Node node) {
            return new NodeResponse(
                    node.id(), null, false,
                    node.kind().code(), node.subtype(), node.content(),
                    node.authorKind().code(),
                    node.knowledgeStatus() == null ? null : node.knowledgeStatus().code());
        }
    }
}
