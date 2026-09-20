package com.specagent.graph;

import com.specagent.common.SharedQuestionStatePort;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Thin implementation of the answer-consumed {@link SharedQuestionStatePort}
 * on top of {@link GraphInvariantValidator}.
 *
 * <p>It adds no logic of its own: the rule is the existing
 * {@code validateSharedQuestionState} method, including its conflict code and
 * fail-closed behaviour. Its only job is to keep the port's dependency
 * direction intact, so the answer package never imports the graph package.
 */
@Component
public class SharedQuestionStatePortAdapter implements SharedQuestionStatePort {

    private final GraphInvariantValidator invariantValidator;

    public SharedQuestionStatePortAdapter(GraphInvariantValidator invariantValidator) {
        this.invariantValidator = invariantValidator;
    }

    @Override
    public void validateSharedQuestionState(UUID projectId, UUID nodeId) {
        invariantValidator.validateSharedQuestionState(projectId, nodeId);
    }
}
