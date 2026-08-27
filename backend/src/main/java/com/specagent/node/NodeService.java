package com.specagent.node;

import com.specagent.common.Ids;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates workspace nodes and advances route tips.
 *
 * <p>Interaction (question) nodes are immutable after creation: question,
 * purpose, and options are fixed, and regeneration creates a replacement
 * node. User-authored knowledge drafts are the exception — they may be edited
 * in place while they remain {@code PROPOSED} (see {@link Node#isUserEditableDraft}).
 *
 * <p>Creating a node advances the owning route's tip to the new node, while
 * preserving the route's existing root node.
 */
@Service
public class NodeService {

    private final NodeRepository nodeRepository;
    private final RouteRepository routeRepository;

    public NodeService(NodeRepository nodeRepository, RouteRepository routeRepository) {
        this.nodeRepository = nodeRepository;
        this.routeRepository = routeRepository;
    }

    public Node createRootNode(UUID projectId,
                               UUID routeId,
                               String question,
                               String purpose,
                               List<NodeOption> options,
                               boolean allowFreeAnswer) {
        return createNode(projectId, routeId, null, null, question, purpose, options, allowFreeAnswer);
    }

    public Node createChildNode(UUID projectId,
                                UUID routeId,
                                UUID parentNodeId,
                                String question,
                                String purpose,
                                List<NodeOption> options,
                                boolean allowFreeAnswer) {
        if (parentNodeId == null) {
            throw new IllegalArgumentException("Child node requires a parent node id");
        }
        return createNode(projectId, routeId, parentNodeId, null, question, purpose, options, allowFreeAnswer);
    }

    /**
     * Creates an immutable replacement node that supersedes a historical node
     * during a regenerate operation. The replacement node shares the target
     * node's parent and carries {@code supersedesNodeId} pointing at the old
     * node. The owning route tip is advanced to the replacement node.
     */
    public Node createReplacementNode(UUID projectId,
                                      UUID routeId,
                                      UUID parentNodeId,
                                      UUID supersedesNodeId,
                                      String question,
                                      String purpose,
                                      List<NodeOption> options,
                                      boolean allowFreeAnswer) {
        if (supersedesNodeId == null) {
            throw new IllegalArgumentException("Replacement node requires a superseded node id");
        }
        return createNode(projectId, routeId, parentNodeId, supersedesNodeId,
                question, purpose, options, allowFreeAnswer);
    }

    /**
     * Creates a non-interaction workspace node (knowledge draft, resource
     * reference, artifact). The payload lives in {@code content}; the legacy
     * {@code question} column stays null for these kinds. Question nodes must
     * keep using the question-specific creation methods.
     */
    public Node createWorkspaceNode(UUID projectId,
                                    UUID routeId,
                                    UUID parentNodeId,
                                    NodeKind kind,
                                    String subtype,
                                    Map<String, Object> content,
                                    NodeAuthorKind authorKind,
                                    KnowledgeStatus knowledgeStatus) {
        if (kind == NodeKind.INTERACTION) {
            throw new IllegalArgumentException(
                    "Interaction nodes must be created through question-specific methods");
        }
        String normalizedSubtype = NodeSubtypes.requireAllowed(kind, subtype);
        UUID nodeId = Ids.random();
        Instant now = Instant.now();
        Node node = new Node(nodeId, projectId, parentNodeId, null, null,
                null, null, List.of(), false, now,
                kind, normalizedSubtype, content, authorKind, knowledgeStatus, null, now);
        nodeRepository.save(node);
        advanceRouteTip(routeId, node);
        return node;
    }

    /**
     * Creates a standalone (floating) workspace draft: same validation as
     * {@link #createWorkspaceNode} but the route tip is never advanced and
     * the node carries no parent, so it starts disconnected from every
     * lineage until the user explicitly connects it.
     */
    public Node createFloatingWorkspaceNode(UUID projectId,
                                            NodeKind kind,
                                            String subtype,
                                            Map<String, Object> content,
                                            NodeAuthorKind authorKind,
                                            KnowledgeStatus knowledgeStatus) {
        if (kind == NodeKind.INTERACTION) {
            throw new IllegalArgumentException(
                    "Interaction nodes must be created through question-specific methods");
        }
        String normalizedSubtype = NodeSubtypes.requireAllowed(kind, subtype);
        Node node = new Node(Ids.random(), projectId, null, null, null,
                null, null, List.of(), false, Instant.now(),
                kind, normalizedSubtype, content, authorKind, knowledgeStatus, null, Instant.now());
        nodeRepository.save(node);
        return node;
    }

    public Optional<Node> getNode(UUID nodeId) {
        return nodeRepository.findById(nodeId);
    }

    /** Every project node including retracted ones (callers filter). */
    public List<Node> listProject(UUID projectId) {
        return nodeRepository.findByProject(projectId);
    }

    /**
     * Edits a still-editable user draft in place. The prior subtype/content
     * must be captured by the caller for the operation log; this method only
     * performs the guarded mutation.
     */
    public Node reviseUserDraft(UUID projectId,
                                UUID nodeId,
                                String subtype,
                                Map<String, Object> content) {
        Node node = requireNodeInProject(projectId, nodeId);
        if (!node.isUserEditableDraft()) {
            throw new IllegalStateException(
                    "Node is not an editable user draft: " + nodeId
                            + " (history-preserving revisions are required instead of edits)");
        }
        String normalizedSubtype = NodeSubtypes.requireAllowed(node.kind(), subtype);
        nodeRepository.updateDraft(nodeId, normalizedSubtype, content, Instant.now());
        return nodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalStateException("Draft node missing after edit: " + nodeId));
    }

    /** Applies an explicit knowledge-state transition to a claim-like node. */
    public Node setKnowledgeStatus(UUID projectId, UUID nodeId, KnowledgeStatus status) {
        Node node = requireNodeInProject(projectId, nodeId);
        if (node.knowledgeStatus() == null) {
            throw new IllegalStateException("Node carries no knowledge status: " + nodeId);
        }
        if (node.knowledgeStatus() == status) {
            return node;
        }
        nodeRepository.updateKnowledgeStatus(nodeId, status, Instant.now());
        return nodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalStateException("Node missing after status update: " + nodeId));
    }

    /**
     * Retracts or restores a node's materialized presence. Retraction is a
     * soft, provenance-preserving operation used by undo compensation; it is
     * only legal for leaf nodes without answers (enforced by callers).
     */
    public void setRetracted(UUID nodeId, boolean retracted) {
        nodeRepository.updateRetracted(nodeId, retracted ? Instant.now() : null);
    }

    private Node createNode(UUID projectId,
                            UUID routeId,
                            UUID parentNodeId,
                            UUID supersedesNodeId,
                            String question,
                            String purpose,
                            List<NodeOption> options,
                            boolean allowFreeAnswer) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Node question must not be blank");
        }
        UUID nodeId = Ids.random();
        Instant now = Instant.now();
        Node node = new Node(nodeId, projectId, parentNodeId, null, supersedesNodeId,
                question, purpose, options, allowFreeAnswer, now,
                NodeKind.INTERACTION, "QUESTION", Map.of(),
                NodeAuthorKind.AGENT, null, null, now);
        nodeRepository.save(node);
        advanceRouteTip(routeId, node);
        return node;
    }

    private void advanceRouteTip(UUID routeId, Node node) {
        // Preserve the route's existing root node when updating tip.
        // If the route has no root yet, set root to the new node.
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalArgumentException("Route not found: " + routeId));
        UUID rootNodeId = route.rootNodeId() != null ? route.rootNodeId() : node.id();
        routeRepository.updateTipAndRoot(routeId, node.id(), rootNodeId, Instant.now());
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
}
