package com.specagent.workspace.node;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:Node.java
 *
 * 用途:探索图中的一个工作区单元。
 *
 * 节点可以是一次交互(澄清问题)、用户或 agent 创作的知识、一个外部
 * 资源引用,或一份生成的产物。交互节点在创建时就把不可变的
 * {@code question}、{@code purpose}、{@code options} 固定下来;重新生成
 * 会创建替换节点,而不是原地修改。非交互节点的负载放在 {@code content}。
 *
 * 早于通用工作区模型创建的遗留行被解释为 agent 创建的
 * {@code INTERACTION/QUESTION} 节点。
 */
public class Node {

    private final UUID id;
    private final UUID projectId;
    private final UUID parentNodeId;
    private final UUID createdByRunId;
    private final UUID supersedesNodeId;
    private final String question;
    private final String purpose;
    private final List<NodeOption> options;
    private final boolean allowFreeAnswer;
    private final boolean allowMultiSelect;
    private final Instant createdAt;
    private final NodeKind kind;
    private final String subtype;
    private final Map<String, Object> content;
    private final NodeAuthorKind authorKind;
    private final KnowledgeStatus knowledgeStatus;
    private final Instant retractedAt;
    private final Instant updatedAt;

    public Node(UUID id,
                UUID projectId,
                UUID parentNodeId,
                UUID createdByRunId,
                UUID supersedesNodeId,
                String question,
                String purpose,
                List<NodeOption> options,
                boolean allowFreeAnswer,
                Instant createdAt) {
        this(id, projectId, parentNodeId, createdByRunId, supersedesNodeId,
                question, purpose, options, allowFreeAnswer, false, createdAt,
                NodeKind.INTERACTION, "QUESTION", Map.of(),
                NodeAuthorKind.AGENT, null, null, createdAt);
    }

    public Node(UUID id,
                UUID projectId,
                UUID parentNodeId,
                UUID createdByRunId,
                UUID supersedesNodeId,
                String question,
                String purpose,
                List<NodeOption> options,
                boolean allowFreeAnswer,
                Instant createdAt,
                NodeKind kind,
                String subtype,
                Map<String, Object> content,
                NodeAuthorKind authorKind,
                KnowledgeStatus knowledgeStatus,
                Instant retractedAt,
                Instant updatedAt) {
        this(id, projectId, parentNodeId, createdByRunId, supersedesNodeId,
                question, purpose, options, allowFreeAnswer, false, createdAt,
                kind, subtype, content, authorKind, knowledgeStatus, retractedAt,
                updatedAt);
    }

    public Node(UUID id,
                UUID projectId,
                UUID parentNodeId,
                UUID createdByRunId,
                UUID supersedesNodeId,
                String question,
                String purpose,
                List<NodeOption> options,
                boolean allowFreeAnswer,
                boolean allowMultiSelect,
                Instant createdAt,
                NodeKind kind,
                String subtype,
                Map<String, Object> content,
                NodeAuthorKind authorKind,
                KnowledgeStatus knowledgeStatus,
                Instant retractedAt,
                Instant updatedAt) {
        this.id = id;
        this.projectId = projectId;
        this.parentNodeId = parentNodeId;
        this.createdByRunId = createdByRunId;
        this.supersedesNodeId = supersedesNodeId;
        this.question = question;
        this.purpose = purpose;
        this.options = options == null ? List.of() : List.copyOf(options);
        this.allowFreeAnswer = allowFreeAnswer;
        this.allowMultiSelect = allowMultiSelect;
        this.createdAt = createdAt;
        this.kind = kind;
        this.subtype = subtype;
        this.content = content == null ? Map.of() : Map.copyOf(content);
        this.authorKind = authorKind;
        this.knowledgeStatus = knowledgeStatus;
        this.retractedAt = retractedAt;
        this.updatedAt = updatedAt;
    }

    public UUID id() {
        return id;
    }

    public UUID projectId() {
        return projectId;
    }

    public UUID parentNodeId() {
        return parentNodeId;
    }

    public boolean isRoot() {
        return parentNodeId == null;
    }

    public UUID createdByRunId() {
        return createdByRunId;
    }

    public UUID supersedesNodeId() {
        return supersedesNodeId;
    }

    public String question() {
        return question;
    }

    public String purpose() {
        return purpose;
    }

    public List<NodeOption> options() {
        return options;
    }

    public boolean allowFreeAnswer() {
        return allowFreeAnswer;
    }

    /** 多选题标记:答案可以同时选中多个选项(不改变 question 的不可变语义)。 */
    public boolean allowMultiSelect() {
        return allowMultiSelect;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public NodeKind kind() {
        return kind;
    }

    public String subtype() {
        return subtype;
    }

    public Map<String, Object> content() {
        return content;
    }

    /** 便捷访问器:取出 {@code content} 中的主要文本负载。 */
    public String contentText() {
        Object text = content.get("text");
        return text instanceof String value && !value.isBlank() ? value : null;
    }

    public NodeAuthorKind authorKind() {
        return authorKind;
    }

    public KnowledgeStatus knowledgeStatus() {
        return knowledgeStatus;
    }

    public Instant retractedAt() {
        return retractedAt;
    }

    public boolean isRetracted() {
        return retractedAt != null;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public boolean isInteraction() {
        return kind == NodeKind.INTERACTION;
    }

    /**
     * 用户创作的知识草稿在仍处于 {@code PROPOSED} 时可以原地编辑。
     * 一旦下游出现持久历史,语义修改必须走修订/替换;已确认的内容
     * 走知识状态流转,而不是自由编辑。
     */
    public boolean isUserEditableDraft() {
        return !isRetracted()
                && authorKind == NodeAuthorKind.USER
                && kind == NodeKind.KNOWLEDGE
                && knowledgeStatus == KnowledgeStatus.PROPOSED;
    }
}
