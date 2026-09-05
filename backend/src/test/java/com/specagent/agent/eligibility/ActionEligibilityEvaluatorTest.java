package com.specagent.agent.eligibility;

import com.specagent.agent.contract.AgentContracts;
import com.specagent.agent.contract.AgentInputSnapshot;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.CapabilityDescriptor;
import com.specagent.agent.contract.ClaimView;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ActionEligibilityEvaluatorTest {

    private static final Path FIXTURES = Path.of("../contracts/fixtures");
    private final ActionEligibilityEvaluator evaluator = new ActionEligibilityEvaluator();

    private AgentRequestEnvelope request() throws Exception {
        return AgentContracts.read(
                Files.readString(FIXTURES.resolve("agent-input-valid.json")),
                AgentRequestEnvelope.class);
    }

    @Test
    void runtimeIdentityChangesDoNotAlterMaskOrBasisHash() throws Exception {
        AgentRequestEnvelope original = request();
        AgentInputSnapshot snapshot = original.snapshot();
        AgentInputSnapshot differentIdentity = new AgentInputSnapshot(
                UUID.randomUUID().toString(),
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                snapshot.routeContext(), snapshot.lineage(), snapshot.effectiveClaims(),
                snapshot.metadata(), snapshot.allowedSourceRefs(),
                snapshot.availableCapabilities(), snapshot.capabilityResults(),
                snapshot.relations(), snapshot.relatedNodes(), snapshot.autonomy());
        AgentRequestEnvelope changed = new AgentRequestEnvelope(
                original.protocolVersion(), UUID.randomUUID(), original.event(), differentIdentity,
                original.capabilities(), original.decisionBudget());

        assertEquivalent(evaluator.evaluate(original), evaluator.evaluate(changed));
    }

    @Test
    void reorderingClaimsDoesNotAlterMaskOrBasisHash() throws Exception {
        AgentRequestEnvelope original = request();
        AgentInputSnapshot snapshot = original.snapshot();
        List<ClaimView> claims = new ArrayList<>(snapshot.effectiveClaims());
        claims.add(new ClaimView("constraint", "部署必须位于境内。", "confirmed",
                1.0, null, null));
        AgentRequestEnvelope ordered = withSnapshot(original,
                copy(snapshot, claims, snapshot.availableCapabilities()));
        Collections.reverse(claims);
        AgentRequestEnvelope reversed = withSnapshot(original,
                copy(snapshot, claims, snapshot.availableCapabilities()));

        assertEquivalent(evaluator.evaluate(ordered), evaluator.evaluate(reversed));
    }

    @Test
    void reorderingIrrelevantCapabilityContextDoesNotAlterMaskOrBasisHash() throws Exception {
        AgentRequestEnvelope original = request();
        AgentInputSnapshot snapshot = original.snapshot();
        List<CapabilityDescriptor> capabilities = new ArrayList<>(List.of(
                new CapabilityDescriptor("read.alpha", "1", "alpha", true, "NONE"),
                new CapabilityDescriptor("read.beta", "1", "beta", true, "NONE")));
        AgentRequestEnvelope ordered = withSnapshot(original,
                copy(snapshot, snapshot.effectiveClaims(), capabilities));
        Collections.reverse(capabilities);
        AgentRequestEnvelope reversed = withSnapshot(original,
                copy(snapshot, snapshot.effectiveClaims(), capabilities));

        assertEquivalent(evaluator.evaluate(ordered), evaluator.evaluate(reversed));
    }

    @Test
    void unresolvedStructuredBlockerProducesStableCreateNodeDenial() throws Exception {
        AgentRequestEnvelope original = request();
        AgentInputSnapshot snapshot = original.snapshot();
        List<ClaimView> claims = new ArrayList<>(snapshot.effectiveClaims());
        claims.add(new ClaimView("open_question", "数据保留期尚未确定。", "unresolved",
                1.0, null, null));

        ActionEligibility result = evaluator.evaluate(withSnapshot(original,
                copy(snapshot, claims, snapshot.availableCapabilities())));

        assertThat(result.eligibleFamilies()).doesNotContain("CREATE_NODE", "WAIT");
        assertThat(result.constraints().get("CREATE_NODE").reasonCodes())
                .containsExactly(ActionEligibilityReasonCode.UNRESOLVED_BLOCKER);
        assertThat(result.constraints().get("WAIT").reasonCodes())
                .containsExactly(ActionEligibilityReasonCode.NO_PENDING_DEPENDENCY);
    }

    private void assertEquivalent(ActionEligibility left, ActionEligibility right) {
        assertThat(right.eligibleFamilies()).isEqualTo(left.eligibleFamilies());
        assertThat(right.constraints()).isEqualTo(left.constraints());
        assertThat(right.basisHash()).isEqualTo(left.basisHash());
    }

    private AgentRequestEnvelope withSnapshot(AgentRequestEnvelope request,
                                              AgentInputSnapshot snapshot) {
        return new AgentRequestEnvelope(
                request.protocolVersion(), request.runId(), request.event(), snapshot,
                request.capabilities(), request.decisionBudget());
    }

    private AgentInputSnapshot copy(AgentInputSnapshot snapshot,
                                    List<ClaimView> claims,
                                    List<CapabilityDescriptor> capabilities) {
        return new AgentInputSnapshot(
                snapshot.snapshotId(), snapshot.contextHash(), snapshot.projectId(),
                snapshot.routeId(), snapshot.anchorNodeId(), snapshot.routeContext(),
                snapshot.lineage(), claims, snapshot.metadata(), snapshot.allowedSourceRefs(),
                capabilities, snapshot.capabilityResults(), snapshot.relations(),
                snapshot.relatedNodes(), snapshot.autonomy());
    }
}
