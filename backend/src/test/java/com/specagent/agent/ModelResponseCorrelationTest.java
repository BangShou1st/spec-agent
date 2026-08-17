package com.specagent.agent;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelResponseCorrelationTest {

    private final UUID projectId = UUID.randomUUID();
    private final UUID routeId = UUID.randomUUID();
    private final UUID agentRunId = UUID.randomUUID();
    private final UUID contextSnapshotId = UUID.randomUUID();

    private ModelRequest request() {
        return new ModelRequest(projectId, routeId, agentRunId, contextSnapshotId,
                AgentTaskType.DRAFT_NODE, "{}", Map.of());
    }

    private ModelResponse response(UUID runId, UUID snapshotId, AgentTaskType taskType) {
        return new ModelResponse(runId, snapshotId, taskType,
                AgentAction.ASK_NEXT_QUESTION, "{}", Map.of());
    }

    @Test
    void matchingCorrelationIsAccepted() {
        assertThatCode(() -> ModelResponseCorrelation.validate(
                request(), response(agentRunId, contextSnapshotId, AgentTaskType.DRAFT_NODE)))
                .doesNotThrowAnyException();
    }

    @Test
    void wrongAgentRunIdIsRejected() {
        assertThatThrownBy(() -> ModelResponseCorrelation.validate(
                request(), response(UUID.randomUUID(), contextSnapshotId, AgentTaskType.DRAFT_NODE)))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("agentRunId");
    }

    @Test
    void wrongContextSnapshotIdIsRejected() {
        assertThatThrownBy(() -> ModelResponseCorrelation.validate(
                request(), response(agentRunId, UUID.randomUUID(), AgentTaskType.DRAFT_NODE)))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("contextSnapshotId");
    }

    @Test
    void wrongTaskTypeIsRejected() {
        assertThatThrownBy(() -> ModelResponseCorrelation.validate(
                request(), response(agentRunId, contextSnapshotId, AgentTaskType.DRAFT_SPEC)))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("taskType");
    }

    @Test
    void nullCorrelationFieldsAreRejectedByTheResponseContract() {
        assertThatThrownBy(() -> new ModelResponse(
                null, contextSnapshotId, AgentTaskType.DRAFT_NODE,
                AgentAction.ASK_NEXT_QUESTION, "{}", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestAgentRunId");
    }
}