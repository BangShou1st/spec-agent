package com.specagent.agent.decision;

import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.contract.AgentProtocol;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AgentResponseEnvelope;
import com.specagent.agent.contract.ObservationView;
import com.specagent.agent.contract.ProposedClaim;
import com.specagent.agent.contract.StateUpdateResult;
import com.specagent.agent.contract.UsageView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic in-JVM decision engine selected only by the explicit
 * {@code spec.agent.brain.engine=fake} configuration. It produces exactly the
 * canonical fake outputs shared with the Python brain's fake model client
 * (see {@code contracts/fixtures/fake-model-*.json}) and passes them through
 * the same fail-closed validator as the remote engine, so tests exercise the
 * identical contract path without HTTP.
 *
 * <p>Normal product configuration never selects this engine.
 */
@Component
@ConditionalOnProperty(name = "spec.agent.brain.engine", havingValue = "fake")
public class LocalDeterministicDecisionEngine implements AgentDecisionEngine {

    @Override
    public AgentResponseEnvelope runStateUpdate(AgentRequestEnvelope request) {
        AgentResponseEnvelope response = new AgentResponseEnvelope(
                AgentProtocol.DECISION_PROTOCOL_VERSION,
                request.runId(),
                new StateUpdateResult(List.of(new ProposedClaim(
                        "goal",
                        "The user clarified the main outcome.",
                        "confirmed",
                        0.9,
                        List.of()))),
                null,
                null,
                new UsageView(1, List.of()),
                Map.of());
        AgentBrainResponseValidator.validateStateUpdate(request, response);
        return response;
    }

    @Override
    public AgentResponseEnvelope runDecision(AgentRequestEnvelope request) {
        UUID snapshotId = UUID.fromString(request.snapshot().snapshotId());
        AgentResponseEnvelope response = new AgentResponseEnvelope(
                AgentProtocol.DECISION_PROTOCOL_VERSION,
                request.runId(),
                null,
                new ObservationView(
                        List.of("The user clarified the main outcome."),
                        List.of("The user must confirm scope boundaries."),
                        List.of(),
                        List.of()),
                new ActionProposal(
                        "REQUEST_USER_INPUT",
                        Map.of(
                                "questionText", "What is the most important outcome?",
                                "options", List.of(Map.of("label", "Clarify the primary goal")),
                                "allowFreeAnswer", true),
                        snapshotId,
                        request.snapshot().contextHash(),
                        List.of()),
                new UsageView(1, List.of()),
                Map.of());
        AgentBrainResponseValidator.validateDecision(request, response);
        return response;
    }
}
