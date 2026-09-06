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
    void unresolvedBlockerAllowsRuiButExcludesOrdinaryCreateNode() {
        ObservationEnvelope observation = runScenario(
                scenario("CAL-UNRESOLVED-BLOCKER", new UserEvent.AnswerTip("answer"),
                        new BrainScript(
                                List.of(new BrainClaim(
                                        "open_question", "blocker", "unresolved", 1.0)),
                                new BrainDecision("REQUEST_USER_INPUT", Map.of(
                                        "questionTextSeed", "clarify-blocker",
                                        "options", List.of(),
                                        "allowFreeAnswer", true)),
                                List.of(), List.of("blocker remains unresolved")),
                        Set.of("REQUEST_USER_INPUT")),
                VariantSpec.base("base", 3L));

        Map<String, Object> eligibility = eligibilityStage(observation);
        assertThat(eligibility)
                .containsEntry("mode", "ENFORCED")
                .containsEntry("selected_family", "REQUEST_USER_INPUT")
                .containsEntry("selected_family_eligible", true)
                .containsEntry("post_selection_veto_invoked", true)
                .containsEntry("post_selection_veto_result", "PASS")
                .containsEntry("java_eligibility_validator_result", "PASS");
        assertThat(String.valueOf(eligibility.get("constraints")))
                .contains("CREATE_NODE=ActionEligibilityConstraint[eligible=false, reasonCodes=[UNRESOLVED_BLOCKER]]");
        assertThat(observation.semanticTrace().stages()).containsKey("POLICY_DECISION");
        assertThat(observation.actualPrimaryAction()).isEqualTo("REQUEST_USER_INPUT");
    }

    private Map<String, Object> eligibilityStage(ObservationEnvelope observation) {
        return observation.semanticTrace().stages().get("ACTION_ELIGIBILITY");
    }

    private ScenarioDefinition scenario(String id, UserEvent event,
                                        BrainScript brainScript,
                                        Set<String> acceptableActions) {
        return new ScenarioDefinition(
                id, "1", "eligibility calibration reproduction",
                new GivenSpec(
                        "calibration-title",
                        List.of(new GraphStep.CreateRootQuestion("root-question", true)),
                        event,
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(), List.of(), brainScript),
                new ExpectSpec(
                        Set.of(), List.of(), acceptableActions, Set.of(),
                        Map.of(), Map.of(), CallBudget.normalAnswerCycle()),
                List.of(VariantSpec.base("base", 1L)));
    }
}
