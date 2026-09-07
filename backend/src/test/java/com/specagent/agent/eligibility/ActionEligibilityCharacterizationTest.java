package com.specagent.agent.eligibility;

import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.contract.AgentEvent;
import com.specagent.agent.contract.AgentContracts;
import com.specagent.agent.contract.AgentInputSnapshot;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.ClaimView;
import com.specagent.agent.contract.CapabilityDescriptor;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

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
    void rejectsDecisionWithoutRuntimeOwnedTypedPersistenceIntent() throws Exception {
        AgentRequestEnvelope request = request();
        ActionProposal proposal = proposal(request, "CREATE_NODE", Map.of(
                "kind", "KNOWLEDGE",
                "subtype", "DECISION",
                "content", Map.of("text", "采用事件驱动架构处理异步工单。")));

        assertIneligible(request, proposal, "MISSING_TYPED_PERSISTENCE_INTENT");
    }

    @Test
    void acceptsDecisionWithRuntimeOwnedTypedPersistenceIntent() throws Exception {
        AgentRequestEnvelope base = request();
        AgentRequestEnvelope authorized = new AgentRequestEnvelope(
                base.protocolVersion(), base.runId(),
                new AgentEvent(base.event().kind(), base.event().anchorNodeId(),
                        base.event().selectedOptionId(), base.event().freeText(),
                        AgentEvent.PersistenceIntent.RECORD_DECISION_NODE),
                base.snapshot(), base.capabilities(), base.decisionBudget());
        ActionProposal proposal = proposal(authorized, "CREATE_NODE", Map.of(
                "kind", "KNOWLEDGE",
                "subtype", "DECISION",
                "content", Map.of("text", "采用事件驱动架构处理异步工单。")));

        ActionEligibility eligibility = evaluator.evaluate(authorized);
        assertThatCode(() -> validator.validateSelection(authorized, proposal, eligibility))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsNoteThatWrapsCurrentAnswerInBoilerplate() throws Exception {
        AgentRequestEnvelope request = request();
        ActionProposal proposal = proposal(request, "CREATE_NODE", Map.of(
                "kind", "KNOWLEDGE",
                "subtype", "NOTE",
                "content", Map.of("text",
                        "用户已经回答：目前通过邮件收集，容易遗漏。该回答已被系统接收。")));

        assertIneligible(request, proposal, "ANSWER_ALREADY_DURABLE");
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
    void allowsOrdinaryNoteCreationWhileConflictIsUnresolved() throws Exception {
        // Frozen principle: constrain execution, not reasoning. An unresolved
        // conflict/open_question never denies CREATE_NODE at the eligibility
        // boundary. A plain KNOWLEDGE/NOTE that does not duplicate durable
        // state passes the validator; policy/runtime safety still applies
        // downstream (auto-executable vs confirmable vs denied).
        AgentRequestEnvelope base = request();
        AgentInputSnapshot snapshot = base.snapshot();
        List<ClaimView> claims = new ArrayList<>(snapshot.effectiveClaims());
        claims.add(new ClaimView("conflict", "交付范围与资源约束互斥。", "unresolved",
                1.0, snapshot.anchorNodeId(), null));
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

        ActionEligibility eligibility = evaluator.evaluate(blocked);
        assertThat(eligibility.eligibleFamilies()).contains("CREATE_NODE");
        assertThatCode(() -> validator.validateSelection(blocked, proposal, eligibility))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsVisibleReadOnlyCapabilityWhileConflictIsUnresolved() throws Exception {
        // Unresolved conflict does not deny INVOKE_CAPABILITY either: a
        // visible read-only capability with grounded arguments stays eligible.
        // Eligibility never grants execution authority — policy still owns
        // auto-executable vs confirmable vs denied.
        AgentRequestEnvelope base = request();
        AgentInputSnapshot snapshot = base.snapshot();
        List<ClaimView> claims = new ArrayList<>(snapshot.effectiveClaims());
        claims.add(new ClaimView("conflict", "交付范围与资源约束互斥。", "unresolved",
                1.0, snapshot.anchorNodeId(), null));
        AgentInputSnapshot withConflictAndCapability = new AgentInputSnapshot(
                snapshot.snapshotId(), snapshot.contextHash(), snapshot.projectId(),
                snapshot.routeId(), snapshot.anchorNodeId(), snapshot.routeContext(),
                snapshot.lineage(), claims, snapshot.metadata(),
                snapshot.allowedSourceRefs(), List.of(new CapabilityDescriptor(
                        "resource.extract", "1", "extract attached resource", true,
                        "NONE")), snapshot.capabilityResults(), snapshot.relations(),
                snapshot.relatedNodes(), snapshot.autonomy());
        AgentRequestEnvelope blocked = new AgentRequestEnvelope(
                base.protocolVersion(), base.runId(), base.event(), withConflictAndCapability,
                base.capabilities(), base.decisionBudget());
        ActionProposal proposal = proposal(blocked, "INVOKE_CAPABILITY", Map.of(
                "capabilityId", "resource.extract",
                "arguments", Map.of("nodeRef",
                        "node:33333333-3333-3333-3333-333333333333")));

        ActionEligibility eligibility = evaluator.evaluate(blocked);
        assertThat(eligibility.eligibleFamilies()).contains("INVOKE_CAPABILITY");
        assertThatCode(() -> validator.validateSelection(blocked, proposal, eligibility))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsNewNonDecisionNodeThatDoesNotDuplicateDurableState() throws Exception {
        AgentRequestEnvelope request = request();
        ActionProposal proposal = proposal(request, "CREATE_NODE", Map.of(
                "kind", "KNOWLEDGE",
                "subtype", "RISK",
                "content", Map.of("text", "邮件网关中断会延迟工单创建。")));

        ActionEligibility eligibility = evaluator.evaluate(request);
        assertThatCode(() -> validator.validateSelection(request, proposal, eligibility))
                .doesNotThrowAnyException();
    }

    @Test
    void allowsVisibleGroundedCapabilityWithoutGrantingExecutionAuthority() throws Exception {
        AgentRequestEnvelope base = request();
        AgentInputSnapshot snapshot = base.snapshot();
        AgentInputSnapshot withCapability = new AgentInputSnapshot(
                snapshot.snapshotId(), snapshot.contextHash(), snapshot.projectId(),
                snapshot.routeId(), snapshot.anchorNodeId(), snapshot.routeContext(),
                snapshot.lineage(), snapshot.effectiveClaims(), snapshot.metadata(),
                snapshot.allowedSourceRefs(), List.of(new CapabilityDescriptor(
                        "resource.extract", "1", "extract attached resource", false,
                        "LOCAL_DURABLE")), snapshot.capabilityResults(), snapshot.relations(),
                snapshot.relatedNodes(), snapshot.autonomy());
        AgentRequestEnvelope request = new AgentRequestEnvelope(
                base.protocolVersion(), base.runId(), base.event(), withCapability,
                base.capabilities(), base.decisionBudget());
        ActionProposal proposal = proposal(request, "INVOKE_CAPABILITY", Map.of(
                "capabilityId", "resource.extract",
                "arguments", Map.of("nodeRef",
                        "node:33333333-3333-3333-3333-333333333333")));

        ActionEligibility eligibility = evaluator.evaluate(request);
        assertThatCode(() -> validator.validateSelection(request, proposal, eligibility))
                .doesNotThrowAnyException();
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
