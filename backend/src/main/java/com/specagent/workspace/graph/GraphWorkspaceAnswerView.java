package com.specagent.workspace.graph;

import com.specagent.workspace.answer.Answer;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read-only answer presentation view on the project graph.
 *
 * <p>Answer identity remains {@code (routeId, nodeId)}: route-specific answers
 * stay separate and are never merged by node. Only safe presentation fields are
 * exposed; patches and raw answer internals never leak.
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
