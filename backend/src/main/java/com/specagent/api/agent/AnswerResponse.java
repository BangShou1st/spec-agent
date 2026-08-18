package com.specagent.api.agent;

import com.specagent.answer.Answer;

import java.time.Instant;
import java.util.UUID;

/**
 * Safe representation of an immutable user answer.
 *
 * <p>{@code selectedOptionId} is a read-only runtime-owned option id reference;
 * it can never be supplied as a creation id. {@code freeText} is user content.
 */
public record AnswerResponse(
        UUID id,
        UUID projectId,
        UUID routeId,
        UUID nodeId,
        String selectedOptionId,
        String freeText,
        Instant createdAt) {

    public static AnswerResponse from(Answer answer) {
        return new AnswerResponse(
                answer.id(),
                answer.projectId(),
                answer.routeId(),
                answer.nodeId(),
                answer.selectedOptionId(),
                answer.freeText(),
                answer.createdAt());
    }
}