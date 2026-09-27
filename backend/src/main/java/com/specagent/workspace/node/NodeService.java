package com.specagent.workspace.node;

import com.specagent.common.Ids;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:NodeService.java
 *
 * 用途:创建工作区节点并推进路线 tip。
 *
 * 交互(问题)节点创建后不可变:question、purpose、options 一经固定,
 * 重新生成会创建替换节点。用户创作的知识草稿是例外——在仍处于
 * {@code PROPOSED} 时可以原地编辑(见 {@link Node#isUserEditableDraft})。
 *
 * 创建节点会把所属路线的 tip 推进到新节点,同时保留路线既有的
 * 根节点。
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
     * 为重答分支创建一个替代的 Question 节点。新节点把旧问题的不可变
     * 语义(question、purpose、options、allowFreeAnswer)复制到一个新的
     * 规范 id 上,并共享旧节点的父节点;它绝不复用旧的规范节点,也绝不
     * 把自己标记为对旧节点的替换/取代。所属路线的 tip 推进到新节点,
     * 这样共享的旧问题保持其唯一不可变的 Answer。
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

    /** 支持多选的重答节点创建(复制源问题的多选标记)。 */
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
     * 创建一个不可变的替换节点,用于重新生成(regenerate)操作时取代
     * 一个历史节点。替换节点与目标节点共享父节点,并携带指向旧节点的
     * {@code supersedesNodeId}。所属路线的 tip 推进到替换节点。
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

    /** 支持多选的替换节点创建(复制被替换问题的多选标记)。 */
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
     * 创建一个非交互工作区节点(知识草稿、资源引用、产物)。负载放在
     * {@code content};这类 kind 的遗留 {@code question} 列保持 null。
     * 问题节点必须继续使用问题专用的创建方法。
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
     * 创建一个独立(悬浮)的工作区草稿:校验与 {@link #createWorkspaceNode}
     * 相同,但路线 tip 绝不前进、节点不带父节点,因此在用户显式接入
     * 之前,它与所有 lineage 都保持脱离。
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

    /** 项目的全部节点,包含已撤回的(由调用方过滤)。 */
    public List<Node> listProject(UUID projectId) {
        return nodeRepository.findByProject(projectId);
    }

    /**
     * 原地编辑一个仍可编辑的用户草稿。先前的 subtype/content 必须由
     * 调用方捕获以写入操作日志;本方法只执行受保护的变更。
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

    /** 对 claim 类节点应用一次显式的知识状态流转。 */
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
     * 撤回或恢复一个节点的实体存在。撤回是软删除、保留出处,
     * 供 undo 补偿使用;只对没有答案的叶子节点合法(由调用方强制)。
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
     * 把路线 tip 推进到新节点。
     *
     * tip 语义:tip 必须始终落在(或停留在)可回答的 INTERACTION 链上。
     * 知识/资源节点可以挂在当前 tip 之下作为出处与布局用途,但它绝不
     * 会"跳过"一个用户仍需回答的问题——把 tip 推进到它上面会埋掉待答
     * 问题、让路线无法作答。因此,非交互节点只在不存在可被取代的
     * INTERACTION tip 时才推进 tip(空路线,或纯知识头)。
     *
     * 这是所有 lineage 写入方(创建、接入、挂资源)共用的唯一
     * tip 推进语义;调用方绝不能直接更新 tip/root,否则两种行为会
     * 再次漂移。
     */
    public void advanceRouteTip(UUID routeId, Node node) {
        RouteTipPort.RouteTip routeTip = routeTipPort.findTip(routeId);
        if (node.kind() != NodeKind.INTERACTION && routeTip.tipNodeId() != null) {
            Node tip = nodeRepository.findById(routeTip.tipNodeId()).orElse(null);
            if (tip != null && tip.kind() == NodeKind.INTERACTION) {
                return;
            }
        }
        // 更新 tip 时保留路线既有的根节点。
        // 若路线尚无根节点,则把新节点设为根。
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
