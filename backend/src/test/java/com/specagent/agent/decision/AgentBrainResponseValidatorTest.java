package com.specagent.agent.decision;

import com.specagent.agent.protocol.AgentContracts;
import com.specagent.agent.protocol.AgentContractException;
import com.specagent.agent.protocol.AgentInputSnapshot;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.AgentResponseEnvelope;
import com.specagent.agent.protocol.ClaimView;
import com.specagent.agent.protocol.ObservationView;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:AgentBrainResponseValidatorTest.java
 *
 * 测试目标:验证 AgentBrainResponseValidator 对大脑响应的 fail-closed 校验——大脑输出
 * 视为不可信输入,凭空捏造的来源引用、过期的基础上下文、未知的动作族、伪造运行时身份、
 * 绕过未解决冲突上报、预算违规等都必须被拒绝。
 */
class AgentBrainResponseValidatorTest {

    private static final Path FIXTURES = Path.of("../contracts/fixtures");

    private String fixture(String name) throws Exception {
        return Files.readString(FIXTURES.resolve(name));
    }

    private AgentRequestEnvelope request() throws Exception {
        return AgentContracts.read(fixture("agent-input-valid.json"), AgentRequestEnvelope.class);
    }

    private AgentRequestEnvelope requestWithUnresolvedConflict() throws Exception {
        AgentRequestEnvelope base = request();
        AgentInputSnapshot snapshot = base.snapshot();
        List<ClaimView> claims = new ArrayList<>(snapshot.effectiveClaims());
        claims.add(new ClaimView(
                "conflict",
                "一次性交付全部功能与仅有一名兼职开发者的资源约束互斥。",
                "unresolved",
                0.95,
                null,
                null));
        AgentInputSnapshot conflicted = new AgentInputSnapshot(
                snapshot.snapshotId(), snapshot.contextHash(), snapshot.projectId(),
                snapshot.routeId(), snapshot.anchorNodeId(), snapshot.routeContext(),
                snapshot.lineage(), claims, snapshot.metadata(), snapshot.allowedSourceRefs(),
                snapshot.availableCapabilities(), snapshot.capabilityResults(),
                snapshot.relations(), snapshot.relatedNodes(), snapshot.autonomy());
        return new AgentRequestEnvelope(
                base.protocolVersion(), base.runId(), base.event(), conflicted,
                base.capabilities(), base.decisionBudget());
    }

    @Test
    void validDecisionFixturePassesValidation() throws Exception {
        AgentRequestEnvelope request = request();
        AgentResponseEnvelope response =
                AgentContracts.read(fixture("decision-response-valid.json"),
                        AgentResponseEnvelope.class);
        assertThatCode(() -> AgentBrainResponseValidator.validateDecision(request, response))
                .doesNotThrowAnyException();
    }

    @Test
    void inventedSourceRefIsRejected() throws Exception {
        AgentRequestEnvelope request = request();
        AgentResponseEnvelope response = AgentContracts.read(
                fixture("decision-response-invalid-invented-source-ref.json"),
                AgentResponseEnvelope.class);
        assertThatThrownBy(() -> AgentBrainResponseValidator.validateDecision(request, response))
                .isInstanceOf(AgentContractException.class)
                .hasMessageContaining("allowed");
    }

    @Test
    void staleBaseContextIsRejected() throws Exception {
        AgentRequestEnvelope request = request();
        AgentResponseEnvelope response = AgentContracts.read(
                fixture("decision-response-invalid-stale-base-context.json"),
                AgentResponseEnvelope.class);
        assertThatThrownBy(() -> AgentBrainResponseValidator.validateDecision(request, response))
                .isInstanceOf(AgentContractException.class);
    }

    @Test
    void unknownActionFamilyIsRejected() throws Exception {
        AgentRequestEnvelope request = request();
        AgentResponseEnvelope response = AgentContracts.read(
                fixture("decision-response-valid.json"), AgentResponseEnvelope.class);
        AgentResponseEnvelope mutated = new AgentResponseEnvelope(
                response.protocolVersion(), response.runId(), null, response.observation(),
                new com.specagent.agent.protocol.ActionProposal(
                        "MARK_RISK", response.actionProposal().payload(),
                        response.actionProposal().baseContextSnapshotId(),
                        response.actionProposal().baseContextHash(),
                        response.actionProposal().sourceRefs(),
                        response.actionProposal().proposalId(),
                        response.actionProposal().idempotencyKey(),
                        response.actionProposal().anchorRefs()),
                response.usage(), response.diagnostics());
        assertThatThrownBy(() -> AgentBrainResponseValidator.validateDecision(request, mutated))
                .isInstanceOf(AgentContractException.class);
    }

    @Test
    void unresolvedConflictAcceptsAnyActionFamilyOnceConflictIsReported() throws Exception {
        // Slice 4:已移除冲突场景下的动作族白名单。只要大脑在 observation.conflicts
        // 中如实上报了冲突,ACTION 的选择在这里不受限制(包括 WAIT)。执行安全由
        // Runtime 的 policy/stale/permission 门禁负责,不属于本契约校验的职责。
        AgentRequestEnvelope request = requestWithUnresolvedConflict();
        AgentResponseEnvelope response = AgentContracts.read(
                fixture("decision-response-valid.json"), AgentResponseEnvelope.class);
        AgentResponseEnvelope mutated = new AgentResponseEnvelope(
                response.protocolVersion(), response.runId(), null,
                new ObservationView(List.of(), List.of(),
                        List.of("交付范围与开发资源约束互斥。"), List.of()),
                new com.specagent.agent.protocol.ActionProposal(
                        "WAIT", Map.of(),
                        response.actionProposal().baseContextSnapshotId(),
                        response.actionProposal().baseContextHash(),
                        List.of(), response.actionProposal().proposalId(),
                        response.actionProposal().idempotencyKey(), List.of()),
                response.usage(), response.diagnostics());

        assertThatCode(() -> AgentBrainResponseValidator.validateDecision(request, mutated))
                .doesNotThrowAnyException();
    }

    @Test
    void unresolvedConflictRequiresObservationConflict() throws Exception {
        AgentRequestEnvelope request = requestWithUnresolvedConflict();
        AgentResponseEnvelope response = AgentContracts.read(
                fixture("decision-response-valid.json"), AgentResponseEnvelope.class);
        AgentResponseEnvelope mutated = new AgentResponseEnvelope(
                response.protocolVersion(), response.runId(), null,
                new ObservationView(List.of(), List.of(), List.of(), List.of()),
                response.actionProposal(), response.usage(), response.diagnostics());

        assertThatThrownBy(() -> AgentBrainResponseValidator.validateDecision(request, mutated))
                .isInstanceOf(AgentContractException.class)
                .hasMessageContaining("observation.conflicts");
    }

    @Test
    void unresolvedConflictAcceptsNonDecisionCreateNodeOnceConflictIsReported() throws Exception {
        // Slice 4:普通 NOTE 节点(非显式 DECISION 节点)在旧白名单下会被拒绝;
        // 白名单移除后,只要如实上报冲突即可通过。
        AgentRequestEnvelope request = requestWithUnresolvedConflict();
        AgentResponseEnvelope response = AgentContracts.read(
                fixture("decision-response-valid.json"), AgentResponseEnvelope.class);
        Map<String, Object> payload = Map.of(
                "kind", "KNOWLEDGE",
                "subtype", "NOTE",
                "content", Map.of("text", "记录冲突双方的约束条件。"));
        AgentResponseEnvelope mutated = new AgentResponseEnvelope(
                response.protocolVersion(), response.runId(), null,
                new ObservationView(List.of(), List.of(),
                        List.of("交付范围与开发资源约束互斥。"), List.of()),
                new com.specagent.agent.protocol.ActionProposal(
                        "CREATE_NODE", payload,
                        response.actionProposal().baseContextSnapshotId(),
                        response.actionProposal().baseContextHash(),
                        List.of(), response.actionProposal().proposalId(),
                        response.actionProposal().idempotencyKey(), List.of()),
                response.usage(), response.diagnostics());

        assertThatCode(() -> AgentBrainResponseValidator.validateDecision(request, mutated))
                .doesNotThrowAnyException();
    }

    @Test
    void wrongRunIdIsRejected() throws Exception {
        AgentRequestEnvelope request = request();
        AgentResponseEnvelope response = AgentContracts.read(
                fixture("decision-response-valid.json"), AgentResponseEnvelope.class);
        AgentResponseEnvelope mutated = new AgentResponseEnvelope(
                response.protocolVersion(), UUID.randomUUID(), null, response.observation(),
                response.actionProposal(), response.usage(), response.diagnostics());
        assertThatThrownBy(() -> AgentBrainResponseValidator.validateDecision(request, mutated))
                .isInstanceOf(AgentContractException.class)
                .hasMessageContaining("runId");
    }

    @Test
    void payloadSmugglingRuntimeOwnedOptionIdIsRejected() throws Exception {
        AgentRequestEnvelope request = request();
        AgentResponseEnvelope response = AgentContracts.read(
                fixture("decision-response-valid.json"), AgentResponseEnvelope.class);
        Map<String, Object> payload = new LinkedHashMap<>(response.actionProposal().payload());
        payload.put("options", java.util.List.of(
                Map.of("label", "x", "id", "88888888-8888-8888-8888-888888888888")));
        AgentResponseEnvelope mutated = new AgentResponseEnvelope(
                response.protocolVersion(), response.runId(), null, response.observation(),
                new com.specagent.agent.protocol.ActionProposal(
                        "REQUEST_USER_INPUT", payload,
                        response.actionProposal().baseContextSnapshotId(),
                        response.actionProposal().baseContextHash(),
                        response.actionProposal().sourceRefs(),
                        response.actionProposal().proposalId(),
                        response.actionProposal().idempotencyKey(),
                        response.actionProposal().anchorRefs()),
                response.usage(), response.diagnostics());
        assertThatThrownBy(() -> AgentBrainResponseValidator.validateDecision(request, mutated))
                .isInstanceOf(AgentContractException.class)
                .hasMessageContaining("runtime-owned");
    }

    @Test
    void modelSuggestedConfidenceCanNeverAuthorizeAnything() throws Exception {
        // 校验器绝不把 confidence/risk 当作授权信号:高置信度的提案仍要经过同样的结构校验。
        AgentRequestEnvelope request = request();
        AgentResponseEnvelope response = AgentContracts.read(
                fixture("decision-response-valid.json"), AgentResponseEnvelope.class);
        assertThatCode(() -> AgentBrainResponseValidator.validateDecision(request, response))
                .doesNotThrowAnyException();
        // 而且无论置信度取值多少,过期的快照 id 都会导致失败。
        assertThatThrownBy(() -> AgentBrainResponseValidator.validateDecision(request,
                new AgentResponseEnvelope(response.protocolVersion(), response.runId(), null,
                        response.observation(),
                        new com.specagent.agent.protocol.ActionProposal(
                                "REQUEST_USER_INPUT", response.actionProposal().payload(),
                                UUID.randomUUID(), response.actionProposal().baseContextHash(),
                                response.actionProposal().sourceRefs(),
                                response.actionProposal().proposalId(),
                                response.actionProposal().idempotencyKey(),
                                response.actionProposal().anchorRefs()),
                        response.usage(), response.diagnostics())))
                .isInstanceOf(AgentContractException.class);
    }

    @Test
    void stateUpdateWithActionProposalIsRejected() throws Exception {
        AgentRequestEnvelope request = request();
        AgentResponseEnvelope decision = AgentContracts.read(
                fixture("decision-response-valid.json"), AgentResponseEnvelope.class);
        assertThatThrownBy(() -> AgentBrainResponseValidator.validateStateUpdate(request, decision))
                .isInstanceOf(AgentContractException.class);
    }
}
