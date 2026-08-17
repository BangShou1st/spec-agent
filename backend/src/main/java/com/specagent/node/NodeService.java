package com.specagent.node;

import com.specagent.common.Ids;
import com.specagent.route.RouteRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Creates immutable clarification nodes and advances route tips.
 *
 * <p>Node question, purpose, and options are fixed at creation and never edited.
 * Regeneration creates a replacement node rather than mutating an existing one.
 * Creating a node advances the owning route's tip to the new node.
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

    public Optional<Node> getNode(UUID nodeId) {
        return nodeRepository.findById(nodeId);
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
                question, purpose, options, allowFreeAnswer, now);
        nodeRepository.save(node);
        routeRepository.updateTipAndRoot(routeId, nodeId, nodeId, Instant.now());
        return node;
    }
}
