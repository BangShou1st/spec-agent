package com.specagent.eval;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:P3BEnforcedCapabilityNecessityTest.java
 *
 * 测试目标:P3-B "工具调用必要性"修复在**强制 eligibility 档位**下的行为证据。
 *
 * 背景:P3-B 基线在 shadow(默认)档位下测出 4/8 的 eligibility false positive,
 * 全部来自同一根因——被调用能力**声明的必填参数**没有出现在提案参数里,
 * 于是一次注定以 {@code arguments.query must be non-blank} 失败、白白消耗一次
 * 调用的提案被放行。默认档位刻意不改变行为(它只记录裁决),因此这一组用例
 * 把模式切到 enforced,给出前后对照的**行为**证据:提案不再成立、能力不再被调用。
 *
 * 本测试不改变任何生产默认配置,也不放宽任何既有断言;它同时验证修复
 * 没有制造新的 false negative(参数齐全的调用与未声明必填字段的能力都不受影响)。
 */
@TestPropertySource(properties = "spec.agent.action-eligibility.mode=enforced")
class P3BEnforcedCapabilityNecessityTest extends EvalHarnessBase {

    @Test
    void invocationMissingADeclaredRequiredArgumentIsVetoedBeforeAnySideEffect() {
        ObservationEnvelope observation = runScenario(
                capabilityScenario("P3B-ENF-MISSING-ARG", Map.of("scope", "PROJECT"), true),
                VariantSpec.base("base", 9001L));

        Map<String, Object> eligibility = eligibilityStage(observation);
        assertThat(eligibility)
                .containsEntry("mode", "ENFORCED")
                .containsEntry("selected_family", "INVOKE_CAPABILITY")
                .containsEntry("post_selection_veto_result", "VETO")
                .containsEntry("java_eligibility_validator_result", "ACTION_INELIGIBLE");
        assertThat(eligibility.get("post_selection_veto_reason_codes"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactly("MISSING_REQUIRED_CAPABILITY_ARGUMENT");
        // 否决发生在 policy/executor 之前:提案永不成立,能力永不执行。
        assertThat(observation.semanticTrace().stages()).doesNotContainKey("POLICY_DECISION");
        assertThat(observation.actualPrimaryAction()).isNull();
        assertThat(observation.executionResult()).startsWith("failed:");
        assertThat(persistedCapabilityInvocations()).isZero();
        assertThat(observation.violations()).isEmpty();
    }

    @Test
    void invocationWithItsDeclaredRequiredArgumentPresentStillExecutes() {
        ObservationEnvelope observation = runScenario(
                capabilityScenario("P3B-ENF-GROUNDED-ARG",
                        Map.of("query", "会议室预约规则", "scope", "PROJECT"), false),
                VariantSpec.base("base", 9002L));

        Map<String, Object> eligibility = eligibilityStage(observation);
        assertThat(eligibility)
                .containsEntry("post_selection_veto_result", "PASS")
                .containsEntry("java_eligibility_validator_result", "PASS");
        assertThat(observation.actualPrimaryAction()).isEqualTo("INVOKE_CAPABILITY");
        assertThat(observation.executionResult()).isEqualTo("completed");
        assertThat(observation.semanticTrace().stages()).containsKey("POLICY_DECISION");
        assertThat(persistedCapabilityInvocations()).isPositive();
        assertThat(observation.violations()).isEmpty();
    }

    @Test
    void capabilityWithoutDeclaredRequiredArgumentsIsUnaffected() {
        // 探针能力没有声明任何必填字段:修复绝不能凭空给它加必填条件,
        // 否则会制造新的 eligibility false negative。
        ScenarioDefinition scenario = new ScenarioDefinition(
                "P3B-ENF-NO-REQUIRED",
                "1",
                "capability with no declared required arguments stays eligible",
                new GivenSpec(
                        "p3b-enf-title",
                        List.of(new GraphStep.CreateRootQuestion("p3b-enf-root", true)),
                        new UserEvent.AnswerTip("p3b-enf-answer"),
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(new CapabilitySpec("eval.decoy.read-only", "NONE", true, Map.of())),
                        List.of(),
                        new BrainScript(
                                List.of(new BrainClaim("goal", "p3b-enf-goal", "confirmed", 0.9)),
                                new BrainDecision("INVOKE_CAPABILITY", Map.of(
                                        "capabilityId", "eval.decoy.read-only",
                                        "arguments", Map.of("nodeRef", "step:tip"))),
                                List.of("p3b-enf-known"),
                                List.of())),
                new ExpectSpec(Set.of(), List.of(),
                        Set.of("INVOKE_CAPABILITY"), Set.of("WAIT", "RESPOND_TO_USER"),
                        Map.of("answers", 1, "patches", 1, "nodes", 0),
                        Map.of("relations", 0), CallBudget.normalAnswerCycle()),
                List.of(VariantSpec.base("base", 9003L)));
        scenario.validate();

        ObservationEnvelope observation = runScenario(scenario, VariantSpec.base("base", 9003L));

        assertThat(eligibilityStage(observation))
                .containsEntry("post_selection_veto_result", "PASS")
                .containsEntry("java_eligibility_validator_result", "PASS");
        assertThat(observation.actualPrimaryAction()).isEqualTo("INVOKE_CAPABILITY");
        assertThat(persistedCapabilityInvocations()).isPositive();
        assertThat(observation.violations()).isEmpty();
    }

    private ScenarioDefinition capabilityScenario(String scenarioId,
                                                  Map<String, Object> arguments,
                                                  boolean vetoExpected) {
        // oracle 必须与本次期望一致:期望被否决时,该动作族就是禁止的;
        // 期望执行时,它才是唯一可接受的动作。声明不一致的 oracle 无法审查。
        Set<String> acceptable = vetoExpected ? Set.of() : Set.of("INVOKE_CAPABILITY");
        Set<String> forbidden = vetoExpected
                ? Set.of("INVOKE_CAPABILITY", "WAIT") : Set.of("WAIT", "RESPOND_TO_USER", "CREATE_NODE");
        ScenarioDefinition scenario = new ScenarioDefinition(
                scenarioId, "1", "P3-B capability necessity enforcement probe",
                new GivenSpec(
                        "p3b-enf-title",
                        List.of(new GraphStep.CreateRootQuestion("p3b-enf-root", true)),
                        new UserEvent.AnswerTip("p3b-enf-answer"),
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(new CapabilitySpec("memory.search", "NONE", true, Map.of())),
                        List.of(),
                        new BrainScript(
                                List.of(new BrainClaim("goal", "p3b-enf-goal", "confirmed", 0.9)),
                                new BrainDecision("INVOKE_CAPABILITY", Map.of(
                                        "capabilityId", "memory.search",
                                        "arguments", arguments)),
                                List.of("p3b-enf-known"),
                                List.of())),
                new ExpectSpec(Set.of(), List.of(), acceptable, forbidden,
                        Map.of(), Map.of(), CallBudget.normalAnswerCycle()),
                List.of(VariantSpec.base("base", 9001L)));
        scenario.validate();
        return scenario;
    }

    private Map<String, Object> eligibilityStage(ObservationEnvelope observation) {
        Map<String, Object> stage = observation.semanticTrace().stages().get("ACTION_ELIGIBILITY");
        assertThat(stage).as("ACTION_ELIGIBILITY stage must be recorded").isNotNull();
        return stage;
    }

    private int persistedCapabilityInvocations() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM capability_invocations WHERE project_id = ?",
                Integer.class, scenarioRunner.lastProjectId());
        return count == null ? 0 : count;
    }
}
