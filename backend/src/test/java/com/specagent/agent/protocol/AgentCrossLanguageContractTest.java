package com.specagent.agent.protocol;

import com.specagent.agent.decision.AgentBrainResponseValidator;
import com.specagent.agent.protocol.AgentContracts;
import com.specagent.agent.protocol.AgentContractException;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.AgentResponseEnvelope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:AgentCrossLanguageContractTest.java
 *
 * 测试目标:验证与 Python 大脑共享的黄金 fixture 契约——{@code contracts/fixtures}
 * 下的 fixture 是跨语言的唯一权威:合法的必须能解析,非法的必须 fail-closed 拒绝。
 * 覆盖请求/响应信封的严格解析、未知字段与未知协议版本拒绝、V2/V3 字段隔离、
 * 能力描述符与技能目录的往返序列化、路由无关 NODE_QUERY 的可空性等场景。
 */
class AgentV2ContractTest {

    private static final Path FIXTURES = Path.of("../contracts/fixtures");

    private String fixture(String name) throws Exception {
        return Files.readString(FIXTURES.resolve(name));
    }

    @Test
    void validRequestFixtureParses() throws Exception {
        AgentRequestEnvelope envelope =
                AgentContracts.read(fixture("agent-input-valid.json"), AgentRequestEnvelope.class);
        assertThat(envelope.runId().toString())
                .isEqualTo("22222222-2222-2222-2222-222222222222");
        assertThat(envelope.snapshot().metadata().projectTitle())
                .isEqualTo("内部工单系统探索");
        assertThat(envelope.snapshot().lineage()).hasSize(2);
        // 线路上的语言只允许通用 Graph 词汇,不允许 question-workflow 命名。
        String wire = fixture("agent-input-valid.json");
        assertThat(wire).doesNotContain("\"question\"");
        assertThat(wire).doesNotContain("DRAFT_NODE");
    }

    @Test
    void v2SerializationOmitsV3EligibilityField() throws Exception {
        AgentRequestEnvelope envelope = AgentContracts.read(
                fixture("agent-input-valid.json"), AgentRequestEnvelope.class);

        assertThat(AgentContracts.write(envelope))
                .doesNotContain("\"actionEligibility\"");
    }

    @Test
    void typedPersistenceIntentRoundTripsInStrictEnvelope() throws Exception {
        String mutated = fixture("agent-input-v3-valid.json");
        int eventStart = mutated.indexOf("\"event\"");
        int eventText = mutated.indexOf(
                "\"freeText\": \"团队需要一个内部工单系统。\"", eventStart);
        String eventFreeText = "\"freeText\": \"团队需要一个内部工单系统。\"";
        mutated = mutated.substring(0, eventText)
                + eventFreeText + ",\"persistenceIntent\": \"RECORD_DECISION_NODE\""
                + mutated.substring(eventText + eventFreeText.length());
        AgentRequestEnvelope request = AgentContracts.read(mutated, AgentRequestEnvelope.class);

        assertThat(request.event().persistenceIntent())
                .isEqualTo(AgentEvent.PersistenceIntent.RECORD_DECISION_NODE);
        assertThat(AgentContracts.write(request))
                .contains("\"persistenceIntent\":\"RECORD_DECISION_NODE\"");
    }

    @Test
    void unknownRequestFieldIsRejected() throws Exception {
        assertThatThrownBy(() -> AgentContracts.read(
                fixture("agent-input-invalid-unknown-field.json"), AgentRequestEnvelope.class))
                .isInstanceOf(AgentContractException.class);
    }

    @Test
    void unknownRequestProtocolVersionIsRejected() throws Exception {
        assertThatThrownBy(() -> AgentContracts.read(
                fixture("agent-input-invalid-unknown-version.json"), AgentRequestEnvelope.class))
                .isInstanceOf(AgentContractException.class);
    }

    @Test
    void validDecisionResponseFixtureParses() throws Exception {
        AgentResponseEnvelope response =
                AgentContracts.read(fixture("decision-response-valid.json"),
                        AgentResponseEnvelope.class);
        assertThat(response.actionProposal().actionFamily()).isEqualTo("REQUEST_USER_INPUT");
        assertThat(response.observation().known()).isNotEmpty();
    }

    @Test
    void validV3EligibilityRequestAndResponsePassStrictValidation() throws Exception {
        AgentRequestEnvelope request = AgentContracts.read(
                fixture("agent-input-v3-valid.json"), AgentRequestEnvelope.class);
        AgentResponseEnvelope response = AgentContracts.read(
                fixture("decision-response-v3-valid.json"), AgentResponseEnvelope.class);

        assertThat(request.actionEligibility().version()).isEqualTo("action-eligibility.v1");
        // 冻结原则:在 eligibility 边界上,未解决的冲突/开放问题绝不否决 CREATE_NODE。
        // V3 fixture 特意保留一个未解决的 open_question 来证明这一点:CREATE_NODE
        // 保持 eligible,而客观前置条件(WAIT 依赖、能力可见性)仍然生效。
        assertThat(request.actionEligibility().eligibleFamilies())
                .contains("CREATE_NODE", "REQUEST_USER_INPUT")
                .doesNotContain("WAIT", "INVOKE_CAPABILITY");
        assertThatCode(() -> AgentBrainResponseValidator.validateDecision(request, response))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "agent-input-v3-invalid-family.json",
            "agent-input-v3-invalid-unknown-field.json",
            "agent-input-v3-invalid-version.json"
    })
    void invalidV3EligibilityRequestsAreRejected(String name) throws Exception {
        assertThatThrownBy(() -> AgentContracts.read(fixture(name), AgentRequestEnvelope.class))
                .isInstanceOf(AgentContractException.class);
    }

    @Test
    void capabilityDescriptorSchemaFieldsRoundTripStrictly() throws Exception {
        AgentRequestEnvelope envelope = AgentContracts.read(
                fixture("agent-input-valid.json"), AgentRequestEnvelope.class);
        var enriched = new AgentRequestEnvelope(
                envelope.protocolVersion(), envelope.runId(), envelope.event(),
                withCapabilities(envelope.snapshot(),
                        new CapabilityDescriptor("mcp.demo.tool", "1",
                                "Demo MCP tool",
                                Map.of("type", "object",
                                        "properties", Map.of("query",
                                                Map.of("type", "string"))),
                                false, "EXTERNAL_REVERSIBLE",
                                List.of("DOCUMENT", "RESOURCE:FILE"))),
                envelope.capabilities(), envelope.decisionBudget(), envelope.actionEligibility());

        AgentRequestEnvelope reparsed = AgentContracts.read(
                AgentContracts.write(enriched), AgentRequestEnvelope.class);
        CapabilityDescriptor descriptor =
                reparsed.snapshot().availableCapabilities().get(0);
        assertThat(descriptor.id()).isEqualTo("mcp.demo.tool");
        assertThat(descriptor.inputSchema())
                .containsEntry("type", "object");
        assertThat(descriptor.supports())
                .containsExactly("DOCUMENT", "RESOURCE:FILE");
        // 旧版 5 参构造器保持与线路上等价(schema/supports 为空)。
        assertThat(AgentContracts.write(new CapabilityDescriptor(
                "legacy.tool", "1", "legacy", true, "NONE")))
                .contains("\"inputSchema\":{}")
                .contains("\"supports\":[]");
    }

    @Test
    void legacyFixturesWithoutSchemaFieldsStillParse() throws Exception {
        // 现有的黄金 fixture 都早于 inputSchema/supports 字段;它们必须以空默认值
        // 继续解析(重放兼容)。
        AgentRequestEnvelope envelope = AgentContracts.read(
                fixture("agent-input-valid.json"), AgentRequestEnvelope.class);
        assertThat(envelope.snapshot().availableCapabilities()).isEmpty();
    }

    @Test
    void skillCatalogRoundTripsStrictlyWithLegacyDefault() throws Exception {
        // Phase 4:availableSkills 是带指纹与截断证据的有界目录;
        // 没有该字段的旧 fixture 解析为空。
        AgentRequestEnvelope legacy = AgentContracts.read(
                fixture("agent-input-valid.json"), AgentRequestEnvelope.class);
        assertThat(legacy.snapshot().availableSkills().skills()).isEmpty();
        assertThat(legacy.snapshot().availableSkills().truncated()).isFalse();

        AgentInputSnapshot snapshot = legacy.snapshot();
        AgentInputSnapshot enriched = new AgentInputSnapshot(
                snapshot.snapshotId(), snapshot.contextHash(), snapshot.projectId(),
                snapshot.routeId(), snapshot.anchorNodeId(), snapshot.routeContext(),
                snapshot.lineage(), snapshot.effectiveClaims(), snapshot.metadata(),
                snapshot.allowedSourceRefs(), snapshot.availableCapabilities(),
                new SkillCatalogView(
                        List.of(new AvailableSkillView("sk-1", "migration-safety",
                                "数据库迁移安全检查", "migration.sql")),
                        false, "fp-1"),
                snapshot.capabilityResults(), snapshot.relations(),
                snapshot.relatedNodes(), snapshot.autonomy());
        AgentRequestEnvelope reparsed = AgentContracts.read(
                AgentContracts.write(new AgentRequestEnvelope(
                        legacy.protocolVersion(), legacy.runId(), legacy.event(),
                        enriched, legacy.capabilities(), legacy.decisionBudget(),
                        legacy.actionEligibility())),
                AgentRequestEnvelope.class);
        assertThat(reparsed.snapshot().availableSkills().skills())
                .extracting(AvailableSkillView::skillId).containsExactly("sk-1");
        assertThat(reparsed.snapshot().availableSkills().fingerprint())
                .isEqualTo("fp-1");
        // 线路上不出现完整 SKILL.md、路径、分数或数据库内部结构。
        assertThat(AgentContracts.write(enriched))
                .doesNotContain("SKILL.md")
                .doesNotContain("embedding")
                .doesNotContain("filesystem");
    }

    private AgentInputSnapshot withCapabilities(AgentInputSnapshot snapshot,
                                                 CapabilityDescriptor... descriptors) {
        return new AgentInputSnapshot(
                snapshot.snapshotId(), snapshot.contextHash(), snapshot.projectId(),
                snapshot.routeId(), snapshot.anchorNodeId(), snapshot.routeContext(),
                snapshot.lineage(), snapshot.effectiveClaims(), snapshot.metadata(),
                snapshot.allowedSourceRefs(), List.of(descriptors),
                snapshot.capabilityResults(), snapshot.relations(),
                snapshot.relatedNodes(), snapshot.autonomy());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "decision-response-v3-invalid-unknown-field.json",
            "decision-response-v3-invalid-version.json"
    })
    void invalidV3DecisionResponseSchemaIsRejected(String name) throws Exception {
        assertThatThrownBy(() -> AgentContracts.read(fixture(name), AgentResponseEnvelope.class))
                .isInstanceOf(AgentContractException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "decision-response-v3-invalid-digest.json",
            "decision-response-v3-invalid-evidence-ref.json"
    })
    void invalidV3DecisionResponseSemanticsAreRejected(String name) throws Exception {
        AgentRequestEnvelope request = AgentContracts.read(
                fixture("agent-input-v3-valid.json"), AgentRequestEnvelope.class);
        AgentResponseEnvelope response = AgentContracts.read(
                fixture(name), AgentResponseEnvelope.class);
        assertThatThrownBy(() -> AgentBrainResponseValidator.validateDecision(request, response))
                .isInstanceOf(AgentContractException.class);
    }

    @Test
    void v2CannotCarryV3EligibilityFields() throws Exception {
        String mutated = fixture("agent-input-v3-valid.json")
                .replace("agent-input.v3", "agent-input.v2");
        assertThatThrownBy(() -> AgentContracts.read(mutated, AgentRequestEnvelope.class))
                .isInstanceOf(AgentContractException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "decision-response-invalid-unknown-action-family.json",
            "decision-response-invalid-invented-source-ref.json",
            "decision-response-invalid-stale-base-context.json"
    })
    void decisionResponseFixturesRoundTripThroughStrictMapper(String name) throws Exception {
        // Schema 层面:这些 fixture 能解析(语义层面的拒绝由校验器负责)。
        AgentResponseEnvelope response =
                AgentContracts.read(fixture(name), AgentResponseEnvelope.class);
        assertThat(response.actionProposal()).isNotNull();
    }

    @Test
    void runtimeOwnedClaimIdInStateUpdateIsRejectedAtSchemaLevel() throws Exception {
        assertThatThrownBy(() -> AgentContracts.read(
                fixture("state-update-response-invalid-runtime-owned-id.json"),
                AgentResponseEnvelope.class))
                .isInstanceOf(AgentContractException.class);
    }

    @Test
    void validStateUpdateFixtureParses() throws Exception {
        AgentResponseEnvelope response =
                AgentContracts.read(fixture("state-update-response-valid.json"),
                        AgentResponseEnvelope.class);
        assertThat(response.stateUpdate().claims()).hasSize(1);
        assertThat(response.actionProposal()).isNull();
    }

    @Test
    void routelessNodeQueryFixtureParsesWithNullRouteIds() throws Exception {
        // Stage C:NODE_QUERY 无路由时可空——浮动节点上的 NODE_QUERY 是唯一允许
        // 携带 null 路由 id 的语义流。契约是跨语言的唯一权威:同一 fixture 同时被
        // Java 严格 mapper 和 Python Pydantic envelope 解析。
        AgentRequestEnvelope envelope = AgentContracts.read(
                fixture("agent-input-routeless-node-query-valid.json"),
                AgentRequestEnvelope.class);
        assertThat(envelope.snapshot().routeId()).isNull();
        assertThat(envelope.snapshot().routeContext().routeId()).isNull();
        assertThat(envelope.event().kind()).isEqualTo("NODE_QUERY");
        assertThat(envelope.snapshot().anchorNodeId())
                .isEqualTo(UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"));
        // 绑定路由的基线 fixture 必须保留路由 id。
        AgentRequestEnvelope baseline = AgentContracts.read(
                fixture("agent-input-valid.json"), AgentRequestEnvelope.class);
        assertThat(baseline.snapshot().routeId()).isNotNull();
        assertThat(baseline.snapshot().routeContext().routeId()).isNotNull();
    }

    @Test
    void nodeQuerySemanticContextFixtureCarriesBoundedOneHopBodyAndDirection()
            throws Exception {
        // Stage C:有界的 1 跳语义上下文——relations 保留方向,relatedNodes 携带
        // 真实的投影节点正文(而不只是不透明的 id),node:<relatedId> 出现在
        // allowedSourceRefs 中,且线路上不出现任何第二跳节点。
        AgentRequestEnvelope envelope = AgentContracts.read(
                fixture("agent-input-node-query-semantic-context-valid.json"),
                AgentRequestEnvelope.class);
        assertThat(envelope.event().kind()).isEqualTo("NODE_QUERY");
        assertThat(envelope.snapshot().routeId())
                .isEqualTo(UUID.fromString("04000000-0000-0000-0000-000000000004"));

        assertThat(envelope.snapshot().relations()).hasSize(1);
        RelationView relation = envelope.snapshot().relations().get(0);
        assertThat(relation.sourceNodeId())
                .isEqualTo(UUID.fromString("05000000-0000-0000-0000-000000000005"));
        assertThat(relation.targetNodeId())
                .isEqualTo(UUID.fromString("06000000-0000-0000-0000-000000000006"));
        assertThat(relation.relationType()).isEqualTo("SUPPORTS");

        assertThat(envelope.snapshot().relatedNodes()).hasSize(1);
        RelatedNodeRef ref = envelope.snapshot().relatedNodes().get(0);
        assertThat(ref.nodeId())
                .isEqualTo(UUID.fromString("06000000-0000-0000-0000-000000000006"));
        assertThat(ref.relationType()).isEqualTo("SUPPORTS");
        assertThat(ref.direction()).isEqualTo("OUTGOING");
        // 相关节点的正文内容确实在线路上传输。
        assertThat(ref.node().body().text())
                .contains("离线队列容量上限 2048 条");
        assertThat(ref.node().kind()).isEqualTo("RESOURCE");

        // 相关节点是一等来源引用,且不会混入 lineage。
        assertThat(envelope.snapshot().allowedSourceRefs())
                .contains("node:06000000-0000-0000-0000-000000000006");
        assertThat(envelope.snapshot().lineage()).hasSize(1);
        assertThat(envelope.snapshot().lineage().get(0).node().id())
                .isEqualTo(UUID.fromString("05000000-0000-0000-0000-000000000005"));
        assertThat(envelope.snapshot().lineage()).noneMatch(entry ->
                entry.node().id().equals(ref.nodeId()));
    }
}
