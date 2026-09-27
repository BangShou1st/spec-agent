package com.specagent.agent.snapshot;

import com.specagent.agent.protocol.AgentContracts;
import com.specagent.agent.protocol.AgentInputSnapshot;
import com.specagent.capability.CapabilityAdapter;
import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.capability.SideEffectClass;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.common.Hashes;
import com.specagent.workspace.context.ContextBuilder;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.graph.GraphCommandService;
import com.specagent.workspace.graph.NodeRelation;
import com.specagent.workspace.graph.NodeRelationType;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.patch.Claim;
import com.specagent.workspace.patch.ClaimKind;
import com.specagent.workspace.patch.ClaimStatus;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.RouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:AgentInputFrozenProjectionIntegrationTest.java
 *
 * 测试目标:冻结 ContextSnapshot 的完整性——一个 ContextSnapshot 一旦投影为面向模型的
 * {@code AgentInputSnapshot},对同一快照的重试必须重放完全相同的语义模型输入,绝不基于
 * 可变的节点正文、相关节点正文、路线标签或能力结果做实时重建。新快照可以看到新的实时
 * 状态,旧快照永远看不到。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AgentInputFrozenProjectionIntegrationTest {

    @TestConfiguration
    static class FrozenTestCapabilityConfig {

        @Bean
        CapabilityAdapter frozenTestCapability() {
            return new CapabilityAdapter() {
                @Override
                public CapabilityDescriptor descriptor() {
                    return new CapabilityDescriptor("test.frozen-probe", "1",
                            "frozen projection probe", Map.of(), Map.of(),
                            false, SideEffectClass.LOCAL_DURABLE, List.of(), List.of());
                }

                // lineage 可见性要求观察可归因:探针把参数中的节点引用回显到
                // source refs,让迟到的结果归属到查询 lineage。没有 run 也没有
                // 引用的行保持隐藏(fail-closed,见 lineage 可见性测试)。
                @Override
                public CapabilityResult invoke(CapabilityInvocation invocation) {
                    return new CapabilityResult(invocation.invocationId(),
                            invocation.invocationKey(), invocation.capabilityId(),
                            CapabilityResult.Status.SUCCEEDED,
                            Map.of("marker", invocation.invocationKey()),
                            nodeRefsFromArguments(invocation.arguments()), Map.of(), List.of());
                }

                private List<String> nodeRefsFromArguments(Map<String, Object> arguments) {
                    List<String> refs = new ArrayList<>();
                    collectRefs(arguments, refs);
                    return List.copyOf(refs);
                }

                private void collectRefs(Object value, List<String> refs) {
                    if (value instanceof String ref && ref.startsWith("node:")) {
                        try {
                            UUID.fromString(ref.substring(5));
                            refs.add(ref);
                        } catch (IllegalArgumentException ignored) {
                        }
                    } else if (value instanceof Map<?, ?> map) {
                        for (Object entry : map.values()) {
                            collectRefs(entry, refs);
                        }
                    } else if (value instanceof List<?> list) {
                        for (Object entry : list) {
                            collectRefs(entry, refs);
                        }
                    }
                }
            };
        }
    }

    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService graphCommandService;
    @Autowired private ContextBuilder contextBuilder;
    @Autowired private AgentInputSnapshotBuilder snapshotBuilder;
    @Autowired private CapabilityRuntime capabilityRuntime;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private RouteRepository routeRepository;
    @Autowired private AnswerService answerService;
    @Autowired private AnswerPatchService answerPatchService;
    @Autowired private com.specagent.workspace.node.NodeService nodeService;

    /**
     * T4——STATE_UPDATE 之后的区分冻结:答案前快照 X 与状态后快照 Y 是不同身份、
     * 不同冻结投影;DECISION 侧投影 Y 暴露刚持久化的补丁 claims——包括未解决的
     * 冲突 claim,让 Conflict Intelligence 对 DECISION 输入保持因果可见性。
     */
    @Test
    void postStateSnapshotIsADistinctFreezeCarryingPersistedClaims() {
        Project project = projectService.createProject("冻结投影-后状态-" + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node question = nodeService.createRootNode(project.id(), routeId,
                "最重要的目标是什么？", null, List.of(), true);

        // 答案前快照 X:STATE_UPDATE 侧的上下文。
        ContextSnapshot preState = contextBuilder.buildFromActiveRoute(
                project.id(), UUID.randomUUID(), ContextOperationType.NORMAL);
        AgentInputSnapshot preProjection = snapshotBuilder.build(preState);

        // STATE_UPDATE 检查点:持久化不可变答案及其补丁,其中包含一个未解决的
        // 冲突 claim,与 Conflict Intelligence 的产出方式一致。
        com.specagent.workspace.answer.Answer answer = answerService.finalizeAnswer(
                project.id(), routeId, question.id(), null, "A 目标优先于 B", "user");
        Claim conflict = Claim.of(ClaimKind.CONFLICT, "A 与 B 在同一时间窗内不能同时成立",
                ClaimStatus.UNRESOLVED, question.id(), answer.id());
        answerPatchService.save(project.id(), routeId, question.id(), answer.id(),
                List.of(conflict), null);

        // 状态后快照 Y:DECISION 调用必须读取的另一个身份;
        // 它的冻结投影携带已持久化的冲突。
        ContextSnapshot postState = contextBuilder.buildForRoute(
                project.id(), routeId, question.id(), UUID.randomUUID(),
                ContextOperationType.NORMAL);
        AgentInputSnapshot postProjection = snapshotBuilder.build(postState);

        assertThat(postState.id()).as("X != Y").isNotEqualTo(preState.id());
        assertThat(postProjection).as("frozen X != frozen Y").isNotEqualTo(preProjection);
        assertThat(postProjection.effectiveClaims()).anySatisfy(claim -> {
            assertThat(claim.kind()).isEqualTo("conflict");
            assertThat(claim.status()).isEqualTo("unresolved");
        });

        // 重放 X 保持冻结:它绝不吸收状态后的 claims。
        assertThat(snapshotBuilder.build(preState)).isEqualTo(preProjection);
        assertThat(snapshotBuilder.build(preState).effectiveClaims())
                .noneSatisfy(claim -> assertThat(claim.kind()).isEqualTo("conflict"));
    }
    /**
     * T1——同一 ContextSnapshot 对可变 LINEAGE 节点正文的重放稳定性:查询上下文的
     * 锚点是可编辑的用户草稿,实时重建会读到编辑后的正文;冻结投影绝不能。
     */
    @Test
    void sameSnapshotReplaysFrozenProjectionAfterLineageNodeBodyMutation() {
        Project project = projectService.createProject("冻结投影-锚点编辑-" + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node draft = graphCommandService.createRootDraftNode(project.id(), routeId,
                "NOTE", Map.of("text", "before"));

        ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(
                project.id(), routeId, draft.id(), "这个节点说了什么？");
        AgentInputSnapshot first = snapshotBuilder.build(snapshot);
        assertThat(first.lineage().get(0).node().body().text()).isEqualTo("before");

        graphCommandService.reviseDraftNode(project.id(), draft.id(),
                "NOTE", Map.of("text", "after"));

        AgentInputSnapshot replayed = snapshotBuilder.build(snapshot);

        assertThat(replayed).as("retry of the same ContextSnapshot must be semantically identical")
                .isEqualTo(first);
        assertThat(replayed.lineage().get(0).node().body().text()).isEqualTo("before");
        assertThat(replayed.contextHash()).isEqualTo(first.contextHash());
        assertThat(replayed.snapshotId()).isEqualTo(first.snapshotId());

        // 新快照可以合法地看到新的实时正文。
        AgentInputSnapshot fresh = snapshotBuilder.build(contextBuilder.buildForNodeQuery(
                project.id(), routeId, draft.id(), "这个节点说了什么？"));
        assertThat(fresh.snapshotId()).isNotEqualTo(first.snapshotId());
        assertThat(fresh.lineage().get(0).node().body().text()).isEqualTo("after");
    }

    /**
     * T2——相关节点重放稳定性:路线绑定的 NODE_QUERY 冻结时看到相关 Knowledge
     * 草稿正文 "before";之后编辑草稿不得改变重放投影,新快照则读到 "after"。
     */
    @Test
    void sameSnapshotReplaysRelatedNodeBodyFrozenAtFreezeTime() {
        Project project = projectService.createProject("冻结投影-关联编辑-" + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node anchor = graphCommandService.createRootDraftNode(project.id(), routeId,
                "NOTE", Map.of("text", "anchor"));
        Node related = graphCommandService.createFloatingDraftNode(project.id(), null,
                "NOTE", Map.of("text", "before"));
        graphCommandService.createSemanticRelation(project.id(),
                anchor.id(), related.id(), NodeRelationType.RELATED_TO,
                NodeRelation.Origin.USER, null, null);

        ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(
                project.id(), routeId, anchor.id(), "关联节点里有什么？");
        AgentInputSnapshot first = snapshotBuilder.build(snapshot);
        assertThat(first.relatedNodes()).hasSize(1);
        assertThat(first.relatedNodes().get(0).node().body().text()).isEqualTo("before");

        graphCommandService.reviseDraftNode(project.id(), related.id(),
                "NOTE", Map.of("text", "after"));

        AgentInputSnapshot replayed = snapshotBuilder.build(snapshot);
        assertThat(replayed).isEqualTo(first);
        assertThat(replayed.relatedNodes().get(0).node().body().text())
                .as("retry must still see the frozen related-node body")
                .isEqualTo("before");

        AgentInputSnapshot fresh = snapshotBuilder.build(contextBuilder.buildForNodeQuery(
                project.id(), routeId, anchor.id(), "关联节点里有什么？"));
        assertThat(fresh.relatedNodes().get(0).node().body().text()).isEqualTo("after");
    }

    /**
     * T3——能力观察重放稳定性:快照冻结之后才完成的结果不得追溯性地出现在
     * 其重放投影中;新快照可以包含它们。
     */
    @Test
    void sameSnapshotExcludesCapabilityResultsCompletedAfterFreeze() {
        Project project = projectService.createProject("冻结投影-能力观察-" + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node draft = graphCommandService.createRootDraftNode(project.id(), routeId,
                "NOTE", Map.of("text", "probe"));

        ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(
                project.id(), routeId, draft.id(), "现在知道什么？");
        AgentInputSnapshot first = snapshotBuilder.build(snapshot);
        assertThat(first.capabilityResults()).isEmpty();

        String key = "frozen-proj-test-" + UUID.randomUUID();
        capabilityRuntime.invoke(key, "test.frozen-probe", project.id(), null,
                Map.of("nodeRef", "node:" + draft.id()));

        AgentInputSnapshot replayed = snapshotBuilder.build(snapshot);
        assertThat(replayed).isEqualTo(first);
        assertThat(replayed.capabilityResults())
                .as("late capability results must not retroactively enter the frozen input")
                .isEmpty();

        AgentInputSnapshot fresh = snapshotBuilder.build(contextBuilder.buildForNodeQuery(
                project.id(), routeId, draft.id(), "现在知道什么？"));
        assertThat(fresh.capabilityResults()).hasSize(1);
        assertThat(fresh.capabilityResults().get(0).content()).containsEntry("marker", key);
    }

    /**
     * T6——无路线 NODE_QUERY 保持冻结安全:浮动节点查询(routeId = null)
     * 冻结与重放都不重新引入任何"必须有路线"的假设。
     */
    @Test
    void routelessNodeQueryFreezesAndReplays() {
        Project project = projectService.createProject("冻结投影-无路线-" + UUID.randomUUID());
        Node floating = graphCommandService.createFloatingDraftNode(project.id(), null,
                "NOTE", Map.of("text", "floating body"));

        ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(
                project.id(), null, floating.id(), "漂浮节点是什么？");
        assertThat(snapshot.routeId()).isNull();

        AgentInputSnapshot first = snapshotBuilder.build(snapshot);
        assertThat(first.routeId()).isNull();
        assertThat(first.routeContext().routeId()).isNull();
        assertThat(first.allowedSourceRefs()).noneMatch(ref -> ref.startsWith("route:"));
        assertThat(first.lineage().get(0).node().body().text()).isEqualTo("floating body");

        graphCommandService.reviseDraftNode(project.id(), floating.id(),
                "NOTE", Map.of("text", "floating edited"));

        AgentInputSnapshot replayed = snapshotBuilder.build(snapshot);
        assertThat(replayed).isEqualTo(first);
        assertThat(replayed.routeId()).isNull();
        assertThat(replayed.lineage().get(0).node().body().text()).isEqualTo("floating body");
    }

    /**
     * 持久身份:每个快照恰好一行冻结投影,绑定快照身份、规范版本、
     * 字节级稳定的载荷及匹配的载荷 hash。
     */
    @Test
    void frozenProjectionIsPersistedOnceWithVerifiableIdentity() {
        Project project = projectService.createProject("冻结投影-持久身份-" + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node draft = graphCommandService.createRootDraftNode(project.id(), routeId,
                "NOTE", Map.of("text", "identity"));

        ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(
                project.id(), routeId, draft.id(), "身份校验");
        AgentInputSnapshot first = snapshotBuilder.build(snapshot);
        snapshotBuilder.build(snapshot);
        snapshotBuilder.build(snapshot);

        Integer rowCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_input_projections WHERE snapshot_id = ?",
                Integer.class, snapshot.id());
        assertThat(rowCount).as("exactly-once freeze").isEqualTo(1);

        String payload = jdbcTemplate.queryForObject(
                "SELECT payload FROM agent_input_projections WHERE snapshot_id = ?",
                String.class, snapshot.id());
        String version = jdbcTemplate.queryForObject(
                "SELECT projection_version FROM agent_input_projections WHERE snapshot_id = ?",
                String.class, snapshot.id());
        String payloadHash = jdbcTemplate.queryForObject(
                "SELECT payload_hash FROM agent_input_projections WHERE snapshot_id = ?",
                String.class, snapshot.id());

        assertThat(version).isEqualTo(AgentInputProjectionRepository.SUPPORTED_PROJECTION_VERSION);
        assertThat(payloadHash).isEqualTo(Hashes.sha256Hex(payload));
        // 冻结载荷是规范的契约投影,解析回来必须与构建器返回的语义快照完全一致。
        assertThat(AgentContracts.read(payload, AgentInputSnapshot.class)).isEqualTo(first);
        assertThat(payload).contains("\"snapshotId\":\"" + snapshot.id() + "\"");
    }

    /**
     * T8——篡改/畸形持久化 fail-closed:hash 不匹配、不支持的投影版本、畸形 JSON
     * 各自必须抛出带类型的损坏失败——绝不静默转为实时重建。
     */
    @Test
    void tamperedOrUnsupportedFrozenPayloadFailsClosed() {
        Project project = projectService.createProject("冻结投影-篡改-" + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node draft = graphCommandService.createRootDraftNode(project.id(), routeId,
                "NOTE", Map.of("text", "audit"));

        // 1. hash 不匹配(冻结后载荷被篡改)。
        ContextSnapshot tampered = contextBuilder.buildForNodeQuery(
                project.id(), routeId, draft.id(), "篡改-hash");
        snapshotBuilder.build(tampered);
        jdbcTemplate.update(
                "UPDATE agent_input_projections SET payload = ? WHERE snapshot_id = ?",
                "{\"snapshotId\":\"tampered\"}", tampered.id());
        assertThatThrownBy(() -> snapshotBuilder.build(tampered))
                .isInstanceOf(FrozenProjectionCorruptedException.class)
                .hasMessageContaining("hash");
        assertThat(countProjections(tampered.id())).isEqualTo(1);

        // 2. 不支持的投影版本。
        ContextSnapshot versioned = contextBuilder.buildForNodeQuery(
                project.id(), routeId, draft.id(), "篡改-version");
        snapshotBuilder.build(versioned);
        jdbcTemplate.update(
                "UPDATE agent_input_projections SET projection_version = ? WHERE snapshot_id = ?",
                "agent-input-projection.v999", versioned.id());
        assertThatThrownBy(() -> snapshotBuilder.build(versioned))
                .isInstanceOf(FrozenProjectionCorruptedException.class)
                .hasMessageContaining("version");

        // 3. 畸形 JSON 载荷(hash 一致,所以必须在解析时失败)。
        ContextSnapshot malformed = contextBuilder.buildForNodeQuery(
                project.id(), routeId, draft.id(), "篡改-json");
        snapshotBuilder.build(malformed);
        String malformedPayload = "{not json at all";
        jdbcTemplate.update(
                "UPDATE agent_input_projections SET payload = ?, payload_hash = ? WHERE snapshot_id = ?",
                malformedPayload, Hashes.sha256Hex(malformedPayload), malformed.id());
        assertThatThrownBy(() -> snapshotBuilder.build(malformed))
                .isInstanceOf(FrozenProjectionCorruptedException.class)
                .hasMessageContaining("parse");

        // 一个仍然完好的兄弟快照在这些失败之后继续正常工作。
        ContextSnapshot healthy = contextBuilder.buildForNodeQuery(
                project.id(), routeId, draft.id(), "健康检查");
        assertThat(snapshotBuilder.build(healthy).lineage().get(0).node().body().text())
                .isEqualTo("audit");
    }

    /**
     * 身份绑定:属于另一个快照身份的冻结行必须被拒绝,而不能被重放。
     */
    @Test
    void frozenPayloadBoundToAnotherSnapshotIdentityFailsClosed() {
        Project project = projectService.createProject("冻结投影-身份绑定-" + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node draft = graphCommandService.createRootDraftNode(project.id(), routeId,
                "NOTE", Map.of("text", "binding"));

        ContextSnapshot stolen = contextBuilder.buildForNodeQuery(
                project.id(), routeId, draft.id(), "身份绑定");
        AgentInputSnapshot projection = snapshotBuilder.build(stolen);

        ContextSnapshot victim = contextBuilder.buildForNodeQuery(
                project.id(), routeId, draft.id(), "身份绑定受害者");
        jdbcTemplate.update(
                "INSERT INTO agent_input_projections (id, snapshot_id, projection_version, payload, payload_hash, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, NOW())",
                UUID.randomUUID(), victim.id(),
                AgentInputProjectionRepository.SUPPORTED_PROJECTION_VERSION,
                AgentContracts.write(projection),
                com.specagent.common.Hashes.sha256Hex(AgentContracts.write(projection)));

        assertThatThrownBy(() -> snapshotBuilder.build(victim))
                .isInstanceOf(FrozenProjectionCorruptedException.class)
                .hasMessageContaining("identity");
    }

    private int countProjections(UUID snapshotId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM agent_input_projections WHERE snapshot_id = ?",
                Integer.class, snapshotId);
        return count == null ? 0 : count;
    }
}
