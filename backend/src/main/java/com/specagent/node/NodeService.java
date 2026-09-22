package com.specagent.node;

import com.specagent.common.Ids;
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
    private final RouteTipPort routeTipPort;
    private final NodeIndexPort nodeIndexPort;

    public NodeService(NodeRepository nodeRepository,
                       RouteTipPort routeTipPort,
                       NodeIndexPort nodeIndexPort) {
        this.nodeRepository = nodeRepository;
        this.routeTipPort = routeTipPort;
        this.nodeIndexPort = nodeIndexPort;
    }

    public Node createRootNode(UUID projectId,
                               UUID routeId,
                               String question,
                               String purpose,
                               List<NodeOption> options,
                               boolean allowFreeAnswer) {
        return createRootNode(projectId, routeId, question, purpose, options, allowFreeAnswer, false);
    }

    public Node createRootNode(UUID projectId,
                               UUID routeId,
                               String question,
                               String purpose,
                               List<NodeOption> options,
                               boolean allowFreeAnswer,
                               boolean allowMultiSelect) {
        return createNode(projectId, routeId, null, null, question, purpose, options, allowFreeAnswer, allowMultiSelect);
    }

    public Node createChildNode(UUID projectId,
                                UUID routeId,
                                UUID parentNodeId,
                                String question,
                                String purpose,
                                List<NodeOption> options,
                                boolean allowFreeAnswer) {
        return createChildNode(projectId, routeId, parentNodeId, question, purpose, options, allowFreeAnswer, false);
    }

    public Node createChildNode(UUID projectId,
                                UUID routeId,
                                UUID parentNodeId,
                                String question,
                                String purpose,
                                List<NodeOption> options,
                                boolean allowFreeAnswer,
                                boolean allowMultiSelect) {
        if (parentNodeId == null) {
            throw new IllegalArgumentException("Child node requires a parent node id");
        }
        return createNode(projectId, routeId, parentNodeId, null, question, purpose, options, allowFreeAnswer, allowMultiSelect);
    }

    /**
     * Creates an alternative Question Node for a re-answer branch. The new
     * node copies the old Question's immutable semantics (question, purpose,
     * options, allowFreeAnswer) onto a fresh canonical id sharing the old
     * parent; it never reuses the old canonical node and never marks itself
     * as its replace/supersede. The owning route tip advances to the new
     * node, so the shared old Question keeps its single immutable Answer.
     */
    public Node createReanswerNode(UUID projectId,
                                   UUID routeId,
                                   UUID parentNodeId,
                                   String question,
                                   String purpose,
                                   List<NodeOption> options,
                                   boolean allowFreeAnswer) {
        return createReanswerNode(projectId, routeId, parentNodeId, question, purpose,
                options, allowFreeAnswer, false);
    }

    /** Multi-select-aware re-answer creation (copies the source question's flag). */
    public Node createReanswerNode(UUID projectId,
                                   UUID routeId,
                                   UUID parentNodeId,
                                   String question,
                                   String purpose,
                                   List<NodeOption> options,
                                   boolean allowFreeAnswer,
                                   boolean allowMultiSelect) {
        return createNode(projectId, routeId, parentNodeId, null,
                question, purpose, options, allowFreeAnswer, allowMultiSelect);
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
        return createReplacementNode(projectId, routeId, parentNodeId, supersedesNodeId,
                question, purpose, options, allowFreeAnswer, false);
    }

    /** Multi-select-aware replacement creation (copies the replaced question's flag). */
    public Node createReplacementNode(UUID projectId,
                                      UUID routeId,
                                      UUID parentNodeId,
                                      UUID supersedesNodeId,
                                      String question,
                                      String purpose,
                                      List<NodeOption> options,
                                      boolean allowFreeAnswer,
                                      boolean allowMultiSelect) {
        if (supersedesNodeId == null) {
            throw new IllegalArgumentException("Replacement node requires a superseded node id");
        }
        return createNode(projectId, routeId, parentNodeId, supersedesNodeId,
                question, purpose, options, allowFreeAnswer, allowMultiSelect);
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
        nodeIndexPort.index(node);
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
        nodeIndexPort.index(node);
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
        Node updated = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalStateException("Draft node missing after edit: " + nodeId));
        nodeIndexPort.index(updated);
        return updated;
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
        Node updated = nodeRepository.findById(nodeId)
                .orElseThrow(() -> new IllegalStateException("Node missing after status update: " + nodeId));
        nodeIndexPort.index(updated);
        return updated;
    }

    /**
     * Retracts or restores a node's materialized presence. Retraction is a
     * soft, provenance-preserving operation used by undo compensation; it is
     * only legal for leaf nodes without answers (enforced by callers).
     */
    public void setRetracted(UUID nodeId, boolean retracted) {
        nodeRepository.updateRetracted(nodeId, retracted ? Instant.now() : null);
        nodeRepository.findById(nodeId).ifPresent(nodeIndexPort::index);
    }

    private Node createNode(UUID projectId,
                            UUID routeId,
                            UUID parentNodeId,
                            UUID supersedesNodeId,
                            String question,
                            String purpose,
                            List<NodeOption> options,
                            boolean allowFreeAnswer,
                            boolean allowMultiSelect) {
        if (question == null || question.isBlank()) {
            throw new IllegalArgumentException("Node question must not be blank");
        }
        UUID nodeId = Ids.random();
        Instant now = Instant.now();
        Node node = new Node(nodeId, projectId, parentNodeId, null, supersedesNodeId,
                question, purpose, options, allowFreeAnswer, allowMultiSelect, now,
                NodeKind.INTERACTION, "QUESTION", Map.of(),
                NodeAuthorKind.AGENT, null, null, now);
        nodeRepository.save(node);
        advanceRouteTip(routeId, node);
        nodeIndexPort.index(node);
        return node;
    }

    /**
     * Advances the route tip to the new node.
     *
     * <p>Tip semantics: the tip must always land on (or stay at) the
     * answerable INTERACTION chain. A knowledge/resource node may hang off the
     * current tip for provenance and layout, but it never SKIPS a question the
     * user still has to answer — advancing the tip onto it would bury the
     * pending question and make the route un-answerable. A non-interaction
     * node therefore advances the tip only when there is no INTERACTION tip to
     * displace (an empty route, or a knowledge-only head).
     *
     * <p>This is THE single tip-advancement semantic for every lineage
     * writer (creation, connect, attach); callers must never update
     * tip/root directly, or the two behaviors drift apart again.
     */
    public void advanceRouteTip(UUID routeId, Node node) {
        RouteTipPort.RouteTip routeTip = routeTipPort.findTip(routeId);
        if (node.kind() != NodeKind.INTERACTION && routeTip.tipNodeId() != null) {
            Node tip = nodeRepository.findById(routeTip.tipNodeId()).orElse(null);
            if (tip != null && tip.kind() == NodeKind.INTERACTION) {
                return;
            }
        }
        // Preserve the route's existing root node when updating tip.
        // If the route has no root yet, set root to the new node.
        UUID rootNodeId = routeTip.rootNodeId() != null ? routeTip.rootNodeId() : node.id();
        routeTipPort.advanceTipAndRoot(routeId, node.id(), rootNodeId, Instant.now());
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
