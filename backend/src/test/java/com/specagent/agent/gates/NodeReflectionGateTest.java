package com.specagent.agent.gates;

import com.specagent.agent.contracts.NodeDraft;
import com.specagent.agent.contracts.ReflectionResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NodeReflectionGateTest {

    private final NodeReflectionGate nodeReflectionGate = new NodeReflectionGate();

    @Test
    void nodeReflectionAcceptsSingleQuestionWithPurpose() {
        ReflectionResult result = nodeReflectionGate.validate(
                new NodeDraft("What is the most important outcome?", "Clarifies the primary goal",
                        List.of(), true));

        assertThat(result.accepted()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
    }

    @Test
    void nodeReflectionRejectsNullDraft() {
        ReflectionResult result = nodeReflectionGate.validate(null);

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors()).containsExactly("Node draft is required");
    }

    @Test
    void nodeReflectionRejectsMultipleQuestionMarks() {
        ReflectionResult result = nodeReflectionGate.validate(
                new NodeDraft("Is it option A? Or option B?", "Purpose", List.of(), true));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors()).contains("Node draft must ask one main question");
    }

    @Test
    void nodeReflectionCountsChineseQuestionMarks() {
        ReflectionResult result = nodeReflectionGate.validate(
                new NodeDraft("你想做什么？为什么？", "Purpose", List.of(), true));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors()).contains("Node draft must ask one main question");
    }

    @Test
    void nodeReflectionRejectsQuestionWithAndWhy() {
        ReflectionResult result = nodeReflectionGate.validate(
                new NodeDraft("What do you need and why do you need it?", "Purpose", List.of(), true));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors()).contains("Node draft appears to ask multiple questions");
    }

    @Test
    void nodeReflectionWarnsWhenPurposeMissing() {
        ReflectionResult result = nodeReflectionGate.validate(
                new NodeDraft("What is the goal?", "", List.of(), true));

        assertThat(result.accepted()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).containsExactly("Node purpose is missing");
    }

    @Test
    void nodeReflectionRejectsNoFreeAnswerWithoutOptions() {
        ReflectionResult result = nodeReflectionGate.validate(
                new NodeDraft("Which option fits best?", "Purpose", List.of(), false));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors()).contains("Node without free answer must provide options");
    }

    @Test
    void nodeReflectionAcceptsNoFreeAnswerWithOptions() {
        ReflectionResult result = nodeReflectionGate.validate(
                new NodeDraft("Which option fits best?", "Purpose",
                        List.of(com.specagent.node.NodeOption.of("A", "First"),
                                com.specagent.node.NodeOption.of("B", "Second")),
                        false));

        assertThat(result.accepted()).isTrue();
    }

    @Test
    void nodeReflectionRejectsBlankAndDuplicateOptionLabels() {
        ReflectionResult result = nodeReflectionGate.validate(
                new NodeDraft("Which fits?", "Purpose",
                        List.of(com.specagent.node.NodeOption.of(" ", "blank"),
                                com.specagent.node.NodeOption.of("Same", "first"),
                                com.specagent.node.NodeOption.of(" same ", "duplicate")),
                        false));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors()).contains("Node option label must not be blank",
                "Node options must have unique labels");
    }
}
