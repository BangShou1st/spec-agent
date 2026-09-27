package com.specagent.eval;

import com.specagent.agent.protocol.AgentEvent;
import org.springframework.test.context.TestPropertySource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:ActionEligibilityEnforcementIntegrationTest.java
 *
 * 测试目标:在 production 路径上以确定性方式复现"强制模式(enforced)
 * 动作资格门禁"的行为。覆盖:重复创建节点在 policy/执行器之前被否决、
 * 授权的 DECISION 节点通过资格校验进入 policy、未解决冲突不阻止普通
 * CREATE_NODE 和可见只读能力的资格判定。
 */
@TestPropertySource(properties = "spec.agent.action-eligibility.mode=enforced")
class ActionEligibilityEnforcementIntegrationTest extends EvalHarnessBase {

    @Test
    void duplicateCreateNodeIsVetoedBeforePolicyOrExecutor() {
        ObservationEnvelope observation = runScenario(
                scenario("CAL-DUPLICATE", new UserEvent.AnswerTip("answer"),
                        new BrainScript(
                                List.of(),
                                new BrainDecision("CREATE_NODE", Map.of(
                                        "kind", "KNOWLEDGE",
                                        "subtype", "NOTE",
                                        "contentTextSeed", "answer")),
                                List.of(), List.of()),
                        Set.of("CREATE_NODE")),
                VariantSpec.base("base", 1L));

        Map<String, Object> eligibility = eligibilityStage(observation);
        assertThat(eligibility)
                .containsEntry("mode", "ENFORCED")
                .containsEntry("selected_family", "CREATE_NODE")
                .containsEntry("selected_family_eligible", true)
                .containsEntry("post_selection_veto_invoked", true)
                .containsEntry("post_selection_veto_result", "VETO")
                .containsEntry("post_selection_veto_reason_codes",
                        List.of("ANSWER_ALREADY_DURABLE"))
                .containsEntry("java_eligibility_validator_result", "ACTION_INELIGIBLE");
        assertThat(eligibility.get("action_ineligible_mapping"))
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("type", "ACTION_INELIGIBLE")
                .containsEntry("enforced", true);
        assertThat(observation.stateDelta()).containsEntry("nodes", 0);
        assertThat(observation.semanticTrace().stages())
                .doesNotContainKey("POLICY_DECISION")
                .doesNotContainKey("EXECUTING");
    }

    @Test
    void authorizedDecisionNodePassesEligibilityAndReachesPolicy() {
        ObservationEnvelope observation = runScenario(
                scenario("CAL-AUTHORIZED-DECISION",
                        new UserEvent.AnswerTip(
                                "authorized-answer",
                                AgentEvent.PersistenceIntent.RECORD_DECISION_NODE),
                        new BrainScript(
                                List.of(),
                                new BrainDecision("CREATE_NODE", Map.of(
                                        "kind", "KNOWLEDGE",
                                        "subtype", "DECISION",
                                        "contentTextSeed", "new-decision")),
                                List.of(), List.of()),
                        Set.of("CREATE_NODE")),
                VariantSpec.base("base", 2L));

        Map<String, Object> eligibility = eligibilityStage(observation);
        assertThat(eligibility)
                .containsEntry("mode", "ENFORCED")
                .containsEntry("selected_family", "CREATE_NODE")
                .containsEntry("selected_family_eligible", true)
                .containsEntry("post_selection_veto_invoked", true)
                .containsEntry("post_selection_veto_result", "PASS")
                .containsEntry("java_eligibility_validator_result", "PASS");
        assertThat(observation.semanticTrace().stages()).containsKey("POLICY_DECISION");
        assertThat(observation.executionResult()).startsWith("awaiting_approval:");
    }

    @Test
    void unresolvedConflictAllowsOrdinaryCreateNodeAndReachesPolicy() {
        // 冻结原则:约束执行,而非约束推理。未解决冲突绝不在资格边界拒绝
        // CREATE_NODE。不与持久化状态重复的普通 KNOWLEDGE/NOTE 通过校验器;
        // policy 与运行时安全仍在下游生效。
        ObservationEnvelope observation = runScenario(
                scenario("CAL-UNRESOLVED-CREATE", new UserEvent.AnswerTip("answer"),
                        new BrainScript(
                                List.of(new BrainClaim(
                                        "conflict", "blocker", "unresolved", 1.0)),
                                new BrainDecision("CREATE_NODE", Map.of(
                                        "kind", "KNOWLEDGE",
                                        "subtype", "NOTE",
                                        "contentTextSeed", "fresh-note")),
                                List.of(), List.of("blocker remains unresolved")),
                        Set.of("CREATE_NODE")),
                VariantSpec.base("base", 3L));

        Map<String, Object> eligibility = eligibilityStage(observation);
        assertThat(eligibility)
                .containsEntry("mode", "ENFORCED")
                .containsEntry("selected_family", "CREATE_NODE")
                .containsEntry("selected_family_eligible", true)
                .containsEntry("post_selection_veto_invoked", true)
                .containsEntry("post_selection_veto_result", "PASS")
                .containsEntry("java_eligibility_validator_result", "PASS");
        assertThat(String.valueOf(eligibility.get("constraints")))
                .doesNotContain("UNRESOLVED_BLOCKER");
        assertThat(observation.semanticTrace().stages()).containsKey("POLICY_DECISION");
        assertThat(observation.actualPrimaryAction()).isEqualTo("CREATE_NODE");
    }

    @Test
    void unresolvedConflictAllowsVisibleReadOnlyCapability() {
        // 未解决冲突同样不拒绝 INVOKE_CAPABILITY:参数有依据的可见只读能力
        // 保持资格。资格判定永不授予执行权限——自动执行/需确认/拒绝
        // 仍由 policy 决定。
        ObservationEnvelope observation = runScenario(
                scenarioWithCapabilities("CAL-UNRESOLVED-CAPABILITY",
                        new UserEvent.AnswerTip("answer"),
                        new BrainScript(
                                List.of(new BrainClaim(
                                        "conflict", "blocker", "unresolved", 1.0)),
                                new BrainDecision("INVOKE_CAPABILITY", Map.of(
                                        "capabilityId", "eval.decoy.read-only",
                                        "arguments", Map.of("nodeRef", "step:tip"))),
                                List.of(), List.of("blocker remains unresolved")),
                        Set.of("INVOKE_CAPABILITY"),
                        List.of(new CapabilitySpec("eval.decoy.read-only",
                                "NONE", true, Map.of()))),
                VariantSpec.base("base", 4L));

        Map<String, Object> eligibility = eligibilityStage(observation);
        assertThat(eligibility)
                .containsEntry("mode", "ENFORCED")
                .containsEntry("selected_family", "INVOKE_CAPABILITY")
                .containsEntry("selected_family_eligible", true)
                .containsEntry("post_selection_veto_invoked", true)
                .containsEntry("post_selection_veto_result", "PASS")
                .containsEntry("java_eligibility_validator_result", "PASS");
        assertThat(String.valueOf(eligibility.get("constraints")))
                .doesNotContain("UNRESOLVED_BLOCKER");
        assertThat(observation.semanticTrace().stages()).containsKey("POLICY_DECISION");
        assertThat(observation.actualPrimaryAction()).isEqualTo("INVOKE_CAPABILITY");
    }

    private Map<String, Object> eligibilityStage(ObservationEnvelope observation) {
        return observation.semanticTrace().stages().get("ACTION_ELIGIBILITY");
    }

    private ScenarioDefinition scenario(String id, UserEvent event,
                                        BrainScript brainScript,
                                        Set<String> acceptableActions) {
        return scenarioWithCapabilities(id, event, brainScript, acceptableActions, List.of());
    }

    private ScenarioDefinition scenarioWithCapabilities(String id, UserEvent event,
                                                        BrainScript brainScript,
                                                        Set<String> acceptableActions,
                                                        List<CapabilitySpec> capabilities) {
        return new ScenarioDefinition(
                id, "1", "eligibility calibration reproduction",
                new GivenSpec(
                        "calibration-title",
                        List.of(new GraphStep.CreateRootQuestion("root-question", true)),
                        event,
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        capabilities, List.of(), brainScript),
                new ExpectSpec(
                        Set.of(), List.of(), acceptableActions, Set.of(),
                        Map.of(), Map.of(), CallBudget.normalAnswerCycle()),
                List.of(VariantSpec.base("base", 1L)));
    }
}
