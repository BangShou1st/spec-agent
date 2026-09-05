package com.specagent.agent.eligibility;

import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.contract.AgentContracts;
import com.specagent.agent.contract.AgentRequestEnvelope;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActionEligibilityGateTest {

    private static final Path FIXTURES = Path.of("../contracts/fixtures");

    private AgentRequestEnvelope request() throws Exception {
        return AgentContracts.read(
                Files.readString(FIXTURES.resolve("agent-input-valid.json")),
                AgentRequestEnvelope.class);
    }

    @Test
    void shadowRecordsDuplicateVetoWithoutChangingV2OrThrowing() throws Exception {
        ActionEligibilityGate gate = gate(ActionEligibilityGate.Mode.SHADOW);
        AgentRequestEnvelope request = request();
        AgentRequestEnvelope prepared = gate.prepareDecisionRequest(request);
        ActionEligibilityGate.Assessment assessment = gate.assess(prepared,
                proposal(prepared, "CREATE_NODE", Map.of(
                        "kind", "KNOWLEDGE", "subtype", "NOTE",
                        "content", Map.of("text", "目前通过邮件收集，容易遗漏。"))));

        assertThat(prepared.protocolVersion()).isEqualTo("agent-input.v2");
        assertThat(prepared.actionEligibility()).isNull();
        assertThat(assessment.wouldVeto()).isTrue();
        assertThat(assessment.reasonCodes())
                .containsExactly(ActionEligibilityReasonCode.ANSWER_ALREADY_DURABLE);
        assertThatCode(() -> gate.enforce(assessment)).doesNotThrowAnyException();
    }

    @Test
    void enforcedModeSendsV3AndThrowsTypedIneligibleFailure() throws Exception {
        ActionEligibilityGate gate = gate(ActionEligibilityGate.Mode.ENFORCED);
        AgentRequestEnvelope prepared = gate.prepareDecisionRequest(request());
        ActionEligibilityGate.Assessment assessment = gate.assess(prepared,
                proposal(prepared, "WAIT", Map.of()));

        assertThat(prepared.protocolVersion()).isEqualTo("agent-input.v3");
        assertThat(prepared.actionEligibility()).isNotNull();
        assertThatThrownBy(() -> gate.enforce(assessment))
                .isInstanceOf(ActionIneligibleException.class)
                .hasMessageContaining("NO_PENDING_DEPENDENCY");
    }

    @Test
    void validSelectionPassesInBothModes() throws Exception {
        for (ActionEligibilityGate.Mode mode : ActionEligibilityGate.Mode.values()) {
            ActionEligibilityGate gate = gate(mode);
            AgentRequestEnvelope prepared = gate.prepareDecisionRequest(request());
            ActionEligibilityGate.Assessment assessment = gate.assess(prepared,
                    proposal(prepared, "REQUEST_USER_INPUT", Map.of(
                            "kind", "INTERACTION",
                            "questionText", "首版需要支持哪些团队？",
                            "options", List.of(),
                            "allowFreeAnswer", true)));
            assertThat(assessment.wouldVeto()).isFalse();
            assertThatCode(() -> gate.enforce(assessment)).doesNotThrowAnyException();
        }
    }

    private ActionEligibilityGate gate(ActionEligibilityGate.Mode mode) {
        return new ActionEligibilityGate(
                new ActionEligibilityEvaluator(), new ActionEligibilityValidator(), mode);
    }

    private ActionProposal proposal(AgentRequestEnvelope request,
                                    String family,
                                    Map<String, Object> payload) {
        return new ActionProposal(
                family, payload, UUID.fromString(request.snapshot().snapshotId()),
                request.snapshot().contextHash(), List.of(), UUID.randomUUID(),
                request.runId().toString(), List.of());
    }
}
