package com.specagent.eval;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:P3BC09AmbiguousGateEnforcementTest.java
 *
 * 测试目标:在**强制 eligibility 档位**下,用**独立的确定性构造**证明 C09 场景 B
 * ({@code P3B-C09-AMBIG})的安全门禁行为:
 * <ul>
 *   <li>缺必填 {@code query} 的 {@code memory.search(scope=PROJECT)} 调用在进入
 *       policy/executor **之前**就被否决:裁决 {@code VETO}、原因码
 *       {@code MISSING_REQUIRED_CAPABILITY_ARGUMENT}、能力**永不执行**;</li>
 *   <li>而本题正确的下一步(就"到底要查哪件事"追问)在 enforced 档位下依然 eligible,
 *       不会被门禁误伤。</li>
 * </ul>
 *
 * 参数用场景 B 的真实可见事实({@link P3BC09NaturalLanguageScenarios#AMBIG_ROOT_QUESTIONS} /
 * {@code AMBIG_ANSWER_TEXTS}),因此这里证明的是"该题面下"的门禁行为,而不是一个
 * 无关探针。绝不依赖真实模型碰巧输出坏动作:坏动作是构造出来的。
 */
@TestPropertySource(properties = "spec.agent.action-eligibility.mode=enforced")
class P3BC09AmbiguousGateEnforcementTest extends EvalHarnessBase {

    @Test
    void missingQueryInvocationOnAmbiguousTaskIsVetoedBeforeAnyEffect() {
        ObservationEnvelope observation = runScenario(missingQueryInvocation(),
                VariantSpec.base("base", 39021L));

        Map<String, Object> eligibility = eligibilityStage(observation);
        assertThat(eligibility)
                .containsEntry("mode", "ENFORCED")
                .containsEntry("selected_family", "INVOKE_CAPABILITY")
                .containsEntry("post_selection_veto_result", "VETO")
                .containsEntry("java_eligibility_validator_result", "ACTION_INELIGIBLE");
        assertThat(eligibility.get("post_selection_veto_reason_codes"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .contains("MISSING_REQUIRED_CAPABILITY_ARGUMENT");
        // 否决发生在 policy/executor 之前:提案永不成立,能力永不执行。
        assertThat(observation.semanticTrace().stages()).doesNotContainKey("POLICY_DECISION");
        assertThat(observation.actualPrimaryAction()).isNull();
        assertThat(observation.executionResult()).startsWith("failed:");
        assertThat(persistedCapabilityInvocations()).isZero();
        assertThat(observation.violations()).isEmpty();
    }

    @Test
    void clarificationOnAmbiguousTaskRemainsEligibleUnderEnforcement() {
        ObservationEnvelope observation = runScenario(clarification(),
                VariantSpec.base("base", 39022L));

        Map<String, Object> eligibility = eligibilityStage(observation);
        assertThat(eligibility)
                .containsEntry("mode", "ENFORCED")
                .containsEntry("post_selection_veto_result", "PASS")
                .containsEntry("java_eligibility_validator_result", "PASS");
        assertThat(observation.actualPrimaryAction()).isEqualTo("REQUEST_USER_INPUT");
        assertThat(observation.executionResult()).isEqualTo("completed");
        assertThat(persistedCapabilityInvocations())
                .as("追问不得触发任何能力调用")
                .isZero();
        assertThat(observation.violations())
                .as("violations: %s", observation.violations())
                .isEmpty();
    }

    /** 场景 B 的真实可见题面 + 一个缺必填 query 的 memory.search 提案。 */
    private static ScenarioDefinition missingQueryInvocation() {
        ScenarioDefinition definition = new ScenarioDefinition(
                "P3B-C09-AMBIG-ENF-BAD", "1",
                "缺必填 query 的调用在场景 B 题面下必须被否决且绝不执行",
                given(new BrainDecision("INVOKE_CAPABILITY", Map.of(
                        "capabilityId", "memory.search",
                        "arguments", Map.of("scope", "PROJECT")))),
                new ExpectSpec(Set.of(), List.of(),
                        Set.of(), Set.of("INVOKE_CAPABILITY", "WAIT"),
                        Map.of(), Map.of(), CallBudget.normalAnswerCycle()),
                List.of(VariantSpec.base("base", 39021L)));
        definition.validate();
        return definition;
    }

    /** 同一题面下,正确追问必须保持 eligible。 */
    private static ScenarioDefinition clarification() {
        ScenarioDefinition definition = new ScenarioDefinition(
                "P3B-C09-AMBIG-ENF-RUI", "1",
                "场景 B 题面下正确追问在 enforced 档位仍应 eligible",
                given(new BrainDecision("REQUEST_USER_INPUT", Map.of(
                        "questionText", "请说明你要查找的具体问题、事项或主题是什么？",
                        "options", List.of(Map.of("label", "补充要查找的具体问题")),
                        "allowFreeAnswer", true))),
                new ExpectSpec(Set.of(), List.of(),
                        Set.of("REQUEST_USER_INPUT"),
                        Set.of("INVOKE_CAPABILITY", "WAIT", "RESPOND_TO_USER"),
                        Map.of("answers", 1, "patches", 1, "nodes", 1),
                        Map.of("capabilityInvocations", 0, "relations", 0),
                        CallBudget.normalAnswerCycle()),
                List.of(VariantSpec.base("base", 39022L)));
        definition.validate();
        return definition;
    }

    private static GivenSpec given(BrainDecision decision) {
        return new GivenSpec(
                "p3b-c09-ambig-enf-title",
                List.of(new GraphStep.CreateRootQuestionText(
                        P3BC09NaturalLanguageScenarios.AMBIG_ROOT_QUESTIONS, true)),
                new UserEvent.AnswerTipText(P3BC09NaturalLanguageScenarios.AMBIG_ANSWER_TEXTS),
                new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                new FocusContextSpec(null, null),
                List.of(new CapabilitySpec("memory.search", "NONE", true, Map.of())),
                List.of(),
                new BrainScript(
                        List.of(new BrainClaim("open_question",
                                "p3b-c09-ambig-enf-unresolved-target", "unresolved", 0.9)),
                        decision,
                        List.of("p3b-c09-ambig-enf-known"), List.of()));
    }

    private Map<String, Object> eligibilityStage(ObservationEnvelope observation) {
        Map<String, Object> stage = observation.semanticTrace().stages().get("ACTION_ELIGIBILITY");
        assertThat(stage).as("ACTION_ELIGIBILITY stage must be recorded").isNotNull();
        return stage;
    }

    private int persistedCapabilityInvocations() {
        java.util.UUID projectId = scenarioRunner.lastProjectId();
        if (projectId == null) {
            return 0;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM capability_invocations WHERE project_id = ?",
                Integer.class, projectId);
        return count == null ? 0 : count;
    }
}
