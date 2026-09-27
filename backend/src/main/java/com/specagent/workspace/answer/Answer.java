package com.specagent.workspace.answer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 文件名:Answer.java
 *
 * 用途:用户对某个节点的不可变回答记录。回答一旦对给定 {@code (routeId, nodeId)}
 * 定稿,就绝不允许被覆盖;重新作答只能通过新建 route、替换节点或回答修订来表达,
 * 而不是修改这条记录。它是 answer 包的核心领域对象,上游由 {@code AnswerService}
 * 定稿落库,下游供 graph/上下文构建等读取。
 *
 * 多选题({@code allowMultiSelect})把完整选择集放在 {@code selectedOptionIds}
 * 中;{@code selectedOptionId} 始终镜像第一个被选中的选项,保证所有单选消费方的
 * 既有语义不变。
 */
public class Answer {

    private final UUID id;
    private final UUID projectId;
    private final UUID routeId;
    private final UUID nodeId;
    private final String selectedOptionId;
    private final List<String> selectedOptionIds;
    private final String freeText;
    private final String createdByUser;
    private final Instant createdAt;

    public Answer(UUID id,
                  UUID projectId,
                  UUID routeId,
                  UUID nodeId,
                  String selectedOptionId,
                  String freeText,
                  String createdByUser,
                  Instant createdAt) {
        this(id, projectId, routeId, nodeId, selectedOptionId,
                selectedOptionId == null ? List.of() : List.of(selectedOptionId),
                freeText, createdByUser, createdAt);
    }

    public Answer(UUID id,
                  UUID projectId,
                  UUID routeId,
                  UUID nodeId,
                  String selectedOptionId,
                  List<String> selectedOptionIds,
                  String freeText,
                  String createdByUser,
                  Instant createdAt) {
        this.id = id;
        this.projectId = projectId;
        this.routeId = routeId;
        this.nodeId = nodeId;
        this.selectedOptionId = selectedOptionId;
        this.selectedOptionIds = selectedOptionIds == null
                ? List.of()
                : List.copyOf(selectedOptionIds);
        this.freeText = freeText;
        this.createdByUser = createdByUser;
        this.createdAt = createdAt;
    }

    public UUID id() {
        return id;
    }

    public UUID projectId() {
        return projectId;
    }

    public UUID routeId() {
        return routeId;
    }

    public UUID nodeId() {
        return nodeId;
    }

    public String selectedOptionId() {
        return selectedOptionId;
    }

    /** 多选题的完整选择集;单选回答只含 0..1 个条目。 */
    public List<String> selectedOptionIds() {
        return selectedOptionIds;
    }

    public String freeText() {
        return freeText;
    }

    public String createdByUser() {
        return createdByUser;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
