package com.specagent.agent.action;

import com.specagent.agent.protocol.*;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Current contract boundaries, not an implementation of asynchronous WAIT/resumption. */
class C16WaitContractTest {
    @Test void deferredCompletionUsesExistingObservationWireButPendingRemainsHostOwned() throws Exception {
        var raw = Files.readString(Path.of("../contracts/fixtures/deferred-capability-completed-observation.json"));
        for (String status : List.of("SUCCEEDED", "FAILED")) {
            var observation = AgentContracts.read(raw.replace("SUCCEEDED", status), CapabilityResultView.class);
            assertThat(observation.status()).isEqualTo(status);
            assertThat(AgentContracts.read(AgentContracts.write(observation), CapabilityResultView.class))
                    .isEqualTo(observation);
        }
        assertThatThrownBy(() -> AgentContracts.read(raw.replace("SUCCEEDED", "PENDING"), CapabilityResultView.class))
                .isInstanceOf(AgentContractException.class);
    }
    private AgentRequestEnvelope request() throws Exception {
        return AgentContracts.read(Files.readString(Path.of("../contracts/fixtures/agent-input-valid.json")),
                AgentRequestEnvelope.class);
    }

    @Test void naturalLanguageOrAResultStatusCannotManufacturePendingEvidence() throws Exception {
        var base = request();
        var s = base.snapshot();
        for (String status : List.of("SUCCEEDED", "FAILED", "RUNNING", "IN_PROGRESS")) {
            var result = new CapabilityResultView("fixture-only", "probe.read", status, Map.of(), List.of(), Map.of());
            var snapshot = new AgentInputSnapshot(s.snapshotId(), s.contextHash(), s.projectId(), s.routeId(),
                    s.anchorNodeId(), s.routeContext(), s.lineage(), s.effectiveClaims(), s.metadata(),
                    s.allowedSourceRefs(), s.availableCapabilities(), List.of(result), s.relations(), s.relatedNodes(), s.autonomy());
            var candidate = new AgentRequestEnvelope(base.protocolVersion(), base.runId(),
                    new AgentEvent("CONTINUE", s.anchorNodeId(), null, "An external operation is still pending"),
                    snapshot, base.capabilities(), base.decisionBudget());
            var eligibility = new ActionEligibilityEvaluator().evaluate(candidate);
            assertThat(eligibility.constraints().get("WAIT").reasonCodes())
                    .containsExactly(ActionEligibilityReasonCode.NO_PENDING_DEPENDENCY);
        }
    }

    @Test void shadowRecordsWaitVetoWhileOnlyEnforcedModeThrows() throws Exception {
        for (String mode : List.of("shadow", "enforced")) {
            var gate = new ActionEligibilityGate(mode);
            var prepared = gate.prepareDecisionRequest(request());
            var proposal = new ActionProposal("WAIT", Map.of("dependencyRef", "invented"),
                    UUID.fromString(prepared.snapshot().snapshotId()), prepared.snapshot().contextHash(),
                    List.of(), UUID.randomUUID(), prepared.runId().toString(), List.of());
            var assessment = gate.assess(prepared, proposal);
            assertThat(assessment.wouldVeto()).isTrue();
            if (mode.equals("shadow")) assertThatCode(() -> gate.enforce(assessment)).doesNotThrowAnyException();
            else assertThatThrownBy(() -> gate.enforce(assessment)).isInstanceOf(ActionIneligibleException.class)
                    .hasMessageContaining("NO_PENDING_DEPENDENCY");
        }
    }

    @Test void legacyWireVersionsRejectInjectedPendingDependencies() throws Exception {
        for (String fixture : List.of("agent-input-valid.json", "agent-input-v3-valid.json")) {
            var raw = (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper()
                    .readTree(Files.readString(Path.of("../contracts/fixtures/" + fixture)));
            ((com.fasterxml.jackson.databind.node.ObjectNode) raw.get("snapshot")).putArray("pendingDependencies");
            assertThatThrownBy(() -> AgentContracts.read(raw.toString(), AgentRequestEnvelope.class))
                    .isInstanceOf(AgentContractException.class);
        }
    }
}
