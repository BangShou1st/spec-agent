package com.specagent.agent;

import com.specagent.agent.contracts.NodeDraft;
import com.specagent.agent.contracts.ReflectionResult;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentContractTest {

    @Test
    void agentActionFromCodeAcceptsSupportedAction() {
        assertThat(AgentAction.fromCode("ask_next_question")).isEqualTo(AgentAction.ASK_NEXT_QUESTION);
        assertThat(AgentAction.fromCode("GENERATE_SPEC")).isEqualTo(AgentAction.GENERATE_SPEC);
    }

    @Test
    void agentActionFromCodeRejectsUnsupportedAction() {
        assertThatThrownBy(() -> AgentAction.fromCode("delete_route"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentAction.fromCode(""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void agentTaskTypeFromCodeAcceptsSupportedTask() {
        assertThat(AgentTaskType.fromCode("gap_analysis")).isEqualTo(AgentTaskType.GAP_ANALYSIS);
        assertThat(AgentTaskType.fromCode("DRAFT_SPEC")).isEqualTo(AgentTaskType.DRAFT_SPEC);
    }

    @Test
    void agentTaskTypeFromCodeRejectsBlankTask() {
        assertThatThrownBy(() -> AgentTaskType.fromCode(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AgentTaskType.fromCode(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void modelRequestRejectsMissingContextSnapshotId() {
        assertThatThrownBy(() -> new ModelRequest(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                AgentTaskType.GAP_ANALYSIS,
                "{}",
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contextSnapshotId");
    }

    @Test
    void modelResponseRejectsMissingAction() {
        assertThatThrownBy(() -> new ModelResponse(
                UUID.randomUUID(),
                UUID.randomUUID(),
                AgentTaskType.GAP_ANALYSIS,
                null,
                "{}",
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("action");
    }

    @Test
    void nodeDraftRejectsBlankQuestion() {
        assertThatThrownBy(() -> new NodeDraft(" ", "purpose", null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("question");
    }

    @Test
    void reflectionResultAcceptedHasAcceptedTrueAndNoErrors() {
        ReflectionResult result = ReflectionResult.acceptedResult();

        assertThat(result.accepted()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }
}