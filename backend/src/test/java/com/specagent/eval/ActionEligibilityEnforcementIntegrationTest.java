package com.specagent.eval;

import com.specagent.agent.contract.AgentEvent;
import org.springframework.test.context.TestPropertySource;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Deterministic production-path reproductions for the enforced eligibility gate. */
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
        // Frozen principle: constrain execution, not reasoning. An unresolved
        // conflict never denies CREATE_NODE at the eligibility boundary. A
        // plain KNOWLEDGE/NOTE that does not duplicate durable state passes
        // the validator; policy/runtime safety still applies downstream.
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
        // Unresolved conflict does not deny INVOKE_CAPABILITY either: a
        // visible read-only capability with grounded arguments stays eligible.
        // Eligibility never grants execution authority — policy still owns
        // auto-executable vs confirmable vs denied.
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
