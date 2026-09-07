package com.specagent.agent.eligibility;

import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.contract.AgentEvent;
import com.specagent.agent.contract.AgentInputSnapshot;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AgentResponseEnvelope;
import com.specagent.agent.contract.AnswerView;
import com.specagent.agent.contract.AutonomyInputs;
import com.specagent.agent.contract.DecisionBudget;
import com.specagent.agent.contract.LineageEntry;
import com.specagent.agent.contract.NodeBodyView;
import com.specagent.agent.contract.NodeView;
import com.specagent.agent.contract.RouteContextView;
import com.specagent.agent.contract.SnapshotMetadata;
import com.specagent.agent.decision.LocalDeterministicDecisionEngine;
import org.junit.jupiter.api.Test;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Regression for the deterministic fake clarification path under enforced
 * eligibility: the fake must never repeat an already-answered question, or
 * the RESOLVED_BLOCKER rule fails the answer run before it reaches terminal.
 */
class DeterministicFakeClarificationTest {

    static final String Q1 = "What is the most important outcome?";
    static final String Q2 = "What is the next most important outcome?";
    static final String Q3 = "What scope boundaries must be confirmed?";

    private final LocalDeterministicDecisionEngine engine = new LocalDeterministicDecisionEngine();
    private final ActionEligibilityGate enforcedGate = new ActionEligibilityGate(
            new ActionEligibilityEvaluator(), new ActionEligibilityValidator(),
            ActionEligibilityGate.Mode.ENFORCED);

    @Test
    void noAnsweredQuestionYieldsFirstClarification() {
        AgentRequestEnvelope request = requestWithAnswered(List.of());

        AgentResponseEnvelope response = engine.runDecision(request);

        assertThat(questionText(response)).isEqualTo(Q1);
    }

    @Test
    void answeredFirstQuestionAdvancesToSecondWithoutResolvedBlocker() {
        AgentRequestEnvelope request = requestWithAnswered(List.of(Q1));

        AgentResponseEnvelope response = engine.runDecision(request);

        assertThat(questionText(response)).isEqualTo(Q2);
        assertThatEligible(request, response.actionProposal());
    }

    @Test
    void answeredFirstTwoQuestionsAdvanceToThirdWithoutResolvedBlocker() {
        AgentRequestEnvelope request = requestWithAnswered(List.of(Q1, Q2));

        AgentResponseEnvelope response = engine.runDecision(request);

        assertThat(questionText(response)).isEqualTo(Q3);
        assertThatEligible(request, response.actionProposal());
    }

    @Test
    void fallbackQuestionNeverRepeatsAnAnsweredQuestion() {
        AgentRequestEnvelope request = requestWithAnswered(List.of(Q1, Q2, Q3));

        AgentResponseEnvelope response = engine.runDecision(request);

        assertThat(normalized(questionText(response)))
                .isNotIn(normalized(Q1), normalized(Q2), normalized(Q3));
        assertThatEligible(request, response.actionProposal());
    }

    private void assertThatEligible(AgentRequestEnvelope request, ActionProposal proposal) {
        AgentRequestEnvelope prepared = enforcedGate.prepareDecisionRequest(request);
        assertThatCode(() -> enforcedGate.enforce(enforcedGate.assess(prepared, proposal)))
                .doesNotThrowAnyException();
    }

    private AgentRequestEnvelope requestWithAnswered(List<String> answeredQuestions) {
        List<LineageEntry> lineage = new ArrayList<>();
        for (String question : answeredQuestions) {
            UUID nodeId = UUID.randomUUID();
            lineage.add(new LineageEntry(
                    new NodeView(nodeId, new NodeBodyView(question, List.of(), true), "INTERACTION"),
                    new AnswerView(UUID.randomUUID(), nodeId, null, "an answer"),
                    List.of()));
        }
        UUID projectId = UUID.randomUUID();
        UUID routeId = UUID.randomUUID();
        UUID anchorNodeId = lineage.isEmpty() ? UUID.randomUUID()
                : lineage.get(lineage.size() - 1).node().id();
        var snapshot = new AgentInputSnapshot(
                UUID.randomUUID().toString(), "context-hash", projectId, routeId, anchorNodeId,
                new RouteContextView(routeId, anchorNodeId, null), lineage, List.of(),
                new SnapshotMetadata("fake regression"), List.of(), List.of(), List.of(),
                List.of(), List.of(), new AutonomyInputs("ADVISOR"));
        return new AgentRequestEnvelope(
                "agent-input.v2", UUID.randomUUID(),
                new AgentEvent("ANSWER_SUBMITTED", anchorNodeId, null, "an answer"),
                snapshot, List.of(), new DecisionBudget(2), null);
    }

    private String questionText(AgentResponseEnvelope response) {
        return (String) response.actionProposal().payload().get("questionText");
    }

    private String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
