package com.specagent.agent.gates;

import com.specagent.agent.decision.ReflectionResult;
import com.specagent.agent.decision.SpecDraft;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:SpecGroundingGateTest.java
 *
 * 测试目标:验证 SpecGroundingGate 门禁对规格草案的落地(grounding)校验——每个规格
 * 小节必须内容非空白且携带来源引用列表,空引用列表视同缺失,空草案直接拒绝。
 */
class SpecGroundingGateTest {

    private final SpecGroundingGate specGroundingGate = new SpecGroundingGate();

    @Test
    void specGroundingAcceptsSectionsWithSourceRefs() {
        ReflectionResult result = specGroundingGate.validate(
                new SpecDraft(
                        Map.of("Goals", "A clear app goal"),
                        List.of("Clarify scope"),
                        Map.of("Goals", List.of("node-1", "answer-2"))));

        assertThat(result.accepted()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void specGroundingRejectsSectionWithoutSourceRefs() {
        ReflectionResult result = specGroundingGate.validate(
                new SpecDraft(
                        Map.of("Goals", "A clear app goal"),
                        List.of(),
                        Map.of()));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors()).contains("Spec section requires source references: Goals");
    }

    @Test
    void specGroundingRejectsBlankSectionContent() {
        ReflectionResult result = specGroundingGate.validate(
                new SpecDraft(
                        Map.of("Goals", "   "),
                        List.of(),
                        Map.of("Goals", List.of("node-1"))));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors()).contains("Spec section content is required: Goals");
    }

    @Test
    void specGroundingRejectsNullDraft() {
        ReflectionResult result = specGroundingGate.validate(null);

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors()).containsExactly("Spec draft is required");
    }

    @Test
    void specGroundingRejectsEmptyRefsListForSection() {
        ReflectionResult result = specGroundingGate.validate(
                new SpecDraft(
                        Map.of("Goals", "A clear app goal"),
                        List.of(),
                        Map.of("Goals", List.of())));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors()).contains("Spec section requires source references: Goals");
    }
}