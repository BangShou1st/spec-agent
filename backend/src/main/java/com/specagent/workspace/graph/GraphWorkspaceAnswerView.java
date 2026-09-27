package com.specagent.workspace.graph;

import com.specagent.workspace.answer.Answer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 文件名:GraphWorkspaceAnswerView.java
 *
 * 用途:项目图上答案的只读展示视图。答案身份保持
 * {@code (routeId, nodeId)}:路线专属的答案彼此独立,绝不按节点合并。
 * 只暴露安全的展示字段;patch 和答案内部数据绝不外泄。
 */
public record GraphWorkspaceAnswerView(
        UUID id,
        UUID routeId,
        UUID ownerRouteId,
        boolean inherited,
        UUID nodeId,
        String selectedOptionId,
        List<String> selectedOptionIds,
        String freeText,
        Instant createdAt) {

    public static GraphWorkspaceAnswerView from(Answer answer) {
        return from(answer, answer.routeId(), false);
    }

    public static GraphWorkspaceAnswerView from(Answer answer,
                                                UUID traversingRouteId,
                                                boolean inherited) {
        return new GraphWorkspaceAnswerView(
                answer.id(), traversingRouteId, answer.routeId(), inherited, answer.nodeId(),
                answer.selectedOptionId(), answer.selectedOptionIds(), answer.freeText(),
                answer.createdAt());
    }
}
