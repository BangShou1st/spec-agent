package com.specagent.agent.eligibility;

import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.contract.AgentContracts;
import com.specagent.agent.contract.AgentInputSnapshot;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.ClaimView;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Generic state-fact characterization for the eligibility boundary. These
 * cases deliberately contain no evaluation scenario ids or benchmark seeds.
 */
class ActionEligibilityCharacterizationTest {

    private static final Path FIXTURES = Path.of("../contracts/fixtures");

    private final ActionEligibilityEvaluator evaluator = new ActionEligibilityEvaluator();
    private final ActionEligibilityValidator validator = new ActionEligibilityValidator();

    private AgentRequestEnvelope request() throws Exception {
        return AgentContracts.read(
                Files.readString(FIXTURES.resolve("agent-input-valid.json")),
                AgentRequestEnvelope.class);
    }

    @Test
    void rejectsCreateNodeNoteThatDuplicatesCurrentAnswer() throws Exception {
        AgentRequestEnvelope request = request();
        ActionProposal proposal = proposal(request, "CREATE_NODE", Map.of(
                "kind", "KNOWLEDGE",
                "subtype", "NOTE",
                "content", Map.of("text", "目前通过邮件收集，容易遗漏。")));

        assertIneligible(request, proposal, "ANSWER_ALREADY_DURABLE");
    }

    @Test
    void rejectsDecisionThatDuplicatesConfirmedClaim() throws Exception {
        AgentRequestEnvelope request = request();
        ActionProposal proposal = proposal(request, "CREATE_NODE", Map.of(
                "kind", "KNOWLEDGE",
                "subtype", "DECISION",
                "content", Map.of("text", "用户希望减少因邮件沟通导致的需求遗漏。")));

        assertIneligible(request, proposal, "CONFIRMED_STATE_ALREADY_DURABLE");
    }

    @Test
    void rejectsWaitWithoutRuntimeOwnedPendingDependency() throws Exception {
        AgentRequestEnvelope request = request();
        ActionProposal proposal = proposal(request, "WAIT", Map.of());

        assertIneligible(request, proposal, "NO_PENDING_DEPENDENCY");
    }

    @Test
    void rejectsCapabilityThatIsNotVisibleInSnapshot() throws Exception {
        AgentRequestEnvelope request = request();
        ActionProposal proposal = proposal(request, "INVOKE_CAPABILITY", Map.of(
                "capabilityId", "private.unexposed.capability",
                "arguments", Map.of()));

        assertIneligible(request, proposal, "CAPABILITY_NOT_VISIBLE");
    }

    @Test
    void rejectsCapabilityArgumentWithUngroundedReference() throws Exception {
        AgentRequestEnvelope request = request();
        ActionProposal proposal = proposal(request, "INVOKE_CAPABILITY", Map.of(
                "capabilityId", "private.unexposed.capability",
                "arguments", Map.of("nodeRef",
                        "node:ffffffff-ffff-ffff-ffff-ffffffffffff")));

        assertIneligible(request, proposal, "UNGROUNDED_CAPABILITY_ARGUMENT");
    }

    @Test
    void rejectsRepeatingAnAlreadyAnsweredQuestion() throws Exception {
        AgentRequestEnvelope request = request();
        ActionProposal proposal = proposal(request, "REQUEST_USER_INPUT", Map.of(
                "kind", "INTERACTION",
                "questionText", "当前如何收集需求？",
                "purpose", "重复询问已经回答的问题",
                "options", List.of(),
                "allowFreeAnswer", true));

        assertIneligible(request, proposal, "RESOLVED_BLOCKER");
    }

    @Test
    void rejectsOrdinaryNodeCreationWhileStructuredBlockerIsUnresolved() throws Exception {
        AgentRequestEnvelope base = request();
        AgentInputSnapshot snapshot = base.snapshot();
        List<ClaimView> claims = new ArrayList<>(snapshot.effectiveClaims());
        claims.add(new ClaimView("open_question", "部署区域尚未确定。", "unresolved",
                1.0, snapshot.anchorNodeId(), null));
        AgentInputSnapshot blockedSnapshot = new AgentInputSnapshot(
                snapshot.snapshotId(), snapshot.contextHash(), snapshot.projectId(),
                snapshot.routeId(), snapshot.anchorNodeId(), snapshot.routeContext(),
                snapshot.lineage(), claims, snapshot.metadata(), snapshot.allowedSourceRefs(),
                snapshot.availableCapabilities(), snapshot.capabilityResults(),
                snapshot.relations(), snapshot.relatedNodes(), snapshot.autonomy());
        AgentRequestEnvelope blocked = new AgentRequestEnvelope(
                base.protocolVersion(), base.runId(), base.event(), blockedSnapshot,
                base.capabilities(), base.decisionBudget());
        ActionProposal proposal = proposal(blocked, "CREATE_NODE", Map.of(
                "kind", "KNOWLEDGE",
                "subtype", "NOTE",
                "content", Map.of("text", "继续设计部署方案。")));

        assertIneligible(blocked, proposal, "UNRESOLVED_BLOCKER");
    }

    private void assertIneligible(AgentRequestEnvelope request,
                                  ActionProposal proposal,
                                  String reasonCode) {
        ActionEligibility eligibility = evaluator.evaluate(request);
        assertThatThrownBy(() -> validator.validateSelection(request, proposal, eligibility))
                .isInstanceOf(ActionIneligibleException.class)
                .hasMessageContaining("ACTION_INELIGIBLE")
                .hasMessageContaining(reasonCode);
    }

    private ActionProposal proposal(AgentRequestEnvelope request,
                                    String family,
                                    Map<String, Object> payload) {
        return new ActionProposal(
                family,
                payload,
                UUID.fromString(request.snapshot().snapshotId()),
                request.snapshot().contextHash(),
                List.of(),
                UUID.randomUUID(),
                request.runId().toString(),
                List.of());
    }
}
