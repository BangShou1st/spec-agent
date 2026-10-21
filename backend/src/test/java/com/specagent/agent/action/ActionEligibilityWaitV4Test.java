package com.specagent.agent.action;

import com.specagent.agent.protocol.*;
import com.specagent.agent.decision.AgentBrainResponseValidator;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Pure protocol/eligibility tests, also compiled by the independent build. */
class ActionEligibilityWaitV4Test {
    AgentRequestEnvelope fixture() throws Exception {
        return AgentContracts.read(Files.readString(Path.of("../contracts/fixtures/agent-input-v4-wait.json")),
                AgentRequestEnvelope.class);
    }
    AgentRequestEnvelope prepared(AgentRequestEnvelope base) {
        var gate = new ActionEligibilityGate("enforced");
        gate.setDependencies(request -> base.pendingDependencies());
        return gate.prepareDecisionRequest(new AgentRequestEnvelope("agent-input.v2", base.runId(), base.event(),
                base.snapshot(), base.capabilities(), base.decisionBudget()));
    }
    ActionProposal wait(AgentRequestEnvelope request, Map<String, Object> payload) {
        return new ActionProposal("WAIT", payload, UUID.fromString(request.snapshot().snapshotId()),
                request.snapshot().contextHash(), List.of(), UUID.randomUUID(), request.runId().toString(), List.of());
    }
    @Test void v4RoundTripsAndExplicitProjectedDependencyEnablesWait() throws Exception {
        var request = prepared(fixture());
        assertThat(AgentContracts.read(AgentContracts.write(request), AgentRequestEnvelope.class)).isEqualTo(request);
        assertThat(request.protocolVersion()).isEqualTo("agent-input.v4");
        assertThat(request.actionEligibility()).isEqualTo(fixture().actionEligibility());
        assertThat(request.actionEligibility().eligibleFamilies()).contains("WAIT");
        var gate = new ActionEligibilityGate("enforced");
        var proposal = wait(request, Map.of("dependencyRef", request.pendingDependencies().dependencies().getFirst().invocationId().toString()));
        assertThatCode(() -> gate.enforce(gate.assess(request, proposal))).doesNotThrowAnyException();
    }
    @Test void emptyInventedAndAdditionalWaitArgumentsAreRejected() throws Exception {
        var request = prepared(fixture());
        for (var payload : List.of(Map.<String,Object>of(), Map.<String,Object>of("dependencyRef", UUID.randomUUID().toString()),
                Map.<String,Object>of("dependencyRef", request.pendingDependencies().dependencies().getFirst().invocationId().toString(), "projectId", request.snapshot().projectId().toString()))) {
            var gate = new ActionEligibilityGate("enforced");
            assertThatThrownBy(() -> gate.enforce(gate.assess(request, wait(request, payload))))
                    .isInstanceOf(ActionIneligibleException.class);
        }
    }
    @Test void legacyVersionsCannotAcquireV4ExtensionAndMissingV4ExtensionFailsClosed() throws Exception {
        var raw = Files.readString(Path.of("../contracts/fixtures/agent-input-v4-wait.json"));
        for (var version : List.of("agent-input.v2", "agent-input.v3"))
            assertThatThrownBy(() -> AgentContracts.read(raw.replace("agent-input.v4", version), AgentRequestEnvelope.class))
                    .isInstanceOf(AgentContractException.class);
        var v3 = Files.readString(Path.of("../contracts/fixtures/agent-input-v3-valid.json"));
        assertThatThrownBy(() -> AgentContracts.read(v3.replace("agent-input.v3", "agent-input.v4"), AgentRequestEnvelope.class))
                .isInstanceOf(AgentContractException.class);
    }
    @Test void foreignProjectionAndDuplicateDependenciesFailBeforeEligibility() throws Exception {
        var request = fixture(); var p = request.pendingDependencies();
        var foreign = new PendingDependencyProjection(p.version(), p.runId(), p.snapshotId(), p.contextHash(),
                UUID.randomUUID(), p.routeId(), p.anchorNodeId(), p.dependencies(), List.of());
        assertThatThrownBy(() -> new AgentRequestEnvelope(request.protocolVersion(), request.runId(), request.event(),
                request.snapshot(), request.capabilities(), request.decisionBudget(), request.actionEligibility(), foreign))
                .isInstanceOf(AgentContractException.class);
        assertThatThrownBy(() -> new PendingDependencyProjection(p.version(), p.runId(), p.snapshotId(), p.contextHash(),
                p.projectId(), p.routeId(), p.anchorNodeId(), List.of(p.dependencies().getFirst(),p.dependencies().getFirst()), List.of()))
                .isInstanceOf(AgentContractException.class);
    }
    @Test void dependencyIdentityParticipatesInEligibilityBasisAndEmptyProjectionDeniesWait() throws Exception {
        var request = prepared(fixture()); var p = request.pendingDependencies();
        var empty = new PendingDependencyProjection(p.version(), p.runId(), p.snapshotId(), p.contextHash(),
                p.projectId(), p.routeId(), p.anchorNodeId(), List.of(), List.of());
        var changed = new AgentRequestEnvelope(request.protocolVersion(), request.runId(), request.event(), request.snapshot(),
                request.capabilities(), request.decisionBudget(), request.actionEligibility(), empty);
        var eligibility = new ActionEligibilityEvaluator().evaluate(changed);
        assertThat(eligibility.eligibleFamilies()).doesNotContain("WAIT");
        assertThat(eligibility.basisHash()).isNotEqualTo(request.actionEligibility().basisHash());
        assertThat(new ActionEligibilityGate("enforced").assess(changed, wait(changed, Map.of())).reasonCodes())
                .contains(ActionEligibilityReasonCode.ELIGIBILITY_BASIS_MISMATCH);
    }
    @Test void responseMustUseV4AndSelectTheConcreteDependency() throws Exception {
        var request = prepared(fixture());
        var proposal = wait(request, Map.of("dependencyRef", request.pendingDependencies().dependencies().getFirst().invocationId().toString()));
        var response = new AgentResponseEnvelope("agent-decision.v4", request.runId(), null,
                new ObservationView(List.of(),List.of(),List.of(),List.of()), proposal, new UsageView(1,List.of()), Map.of(),
                request.actionEligibility().version(), request.actionEligibility().basisHash(), List.of());
        assertThatCode(() -> AgentBrainResponseValidator.validateDecision(request, response)).doesNotThrowAnyException();
        var wrong = new AgentResponseEnvelope("agent-decision.v3", response.runId(), null, response.observation(), proposal,
                response.usage(), Map.of(), response.selectedEligibilityVersion(), response.selectedEligibilityBasisHash(), List.of());
        assertThatThrownBy(() -> AgentBrainResponseValidator.validateDecision(request, wrong)).isInstanceOf(AgentContractException.class);
    }
}
