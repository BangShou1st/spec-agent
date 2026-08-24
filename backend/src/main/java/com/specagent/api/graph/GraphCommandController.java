package com.specagent.api.graph;

import com.specagent.graph.GraphCommandService;
import com.specagent.graph.GraphOperation;
import com.specagent.graph.NodeRelation;
import com.specagent.graph.NodeRelationType;
import com.specagent.graph.UndoRedoService;
import com.specagent.node.KnowledgeStatus;
import com.specagent.node.Node;
import com.specagent.readmodel.graph.GraphWorkspaceRelationView;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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

    public GraphCommandController(GraphCommandService commandService,
                                  UndoRedoService undoRedoService) {
        this.commandService = commandService;
        this.undoRedoService = undoRedoService;
    }

    /** Creates the first (root) draft node on an empty route. Zero model calls. */
    @PostMapping("/nodes")
    public ResponseEntity<NodeResponse> createRootDraftNode(@PathVariable UUID projectId,
                                                             @RequestBody CreateDraftNodeRequest request) {
        Node node = com.specagent.api.common.CommandExecution.execute(() -> commandService.createRootDraftNode(
                projectId, request.routeId(), request.subtype(), request.content()));
        return ResponseEntity.status(HttpStatus.CREATED).body(NodeResponse.from(node, request.routeId(), false));
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
        GraphCommandService.ContinuationResult result = commandService.appendContinuation(
                projectId, request.routeId(), nodeId, request.subtype(), request.content());
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
        Node node = commandService.attachResource(
                projectId, request.routeId(), request.parentNodeId(),
                request.subtype(), request.content());
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
        NodeRelation relation = commandService.createSemanticRelation(
                projectId,
                request.sourceNodeId(),
                request.targetNodeId(),
                NodeRelationType.fromCode(request.relationType()),
                NodeRelation.Origin.USER,
                null,
                null);
        return ResponseEntity.status(HttpStatus.CREATED).body(GraphWorkspaceRelationView.from(relation));
    }

    /** Typed operation log for audits and undo/redo affordances. */
    @GetMapping("/graph-operations")
    public List<GraphOperation> listOperations(@PathVariable UUID projectId) {
        return commandService.listOperations(projectId);
    }

    @GetMapping("/graph-operations/availability")
    public Map<String, Boolean> undoRedoAvailability(@PathVariable UUID projectId) {
        return Map.of(
                "canUndo", undoRedoService.canUndo(projectId),
                "canRedo", undoRedoService.canRedo(projectId));
    }

    @PostMapping("/graph-operations/undo")
    public UndoRedoService.UndoRedoResult undo(@PathVariable UUID projectId) {
        return undoRedoService.undo(projectId);
    }

    @PostMapping("/graph-operations/redo")
    public UndoRedoService.UndoRedoResult redo(@PathVariable UUID projectId) {
        return undoRedoService.redo(projectId);
    }

    // ------------------------------------------------------------------
    // Request/response records
    // ------------------------------------------------------------------

    public record CreateDraftNodeRequest(UUID routeId, String subtype, Map<String, Object> content) {
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
    }
}
