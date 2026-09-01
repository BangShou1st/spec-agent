package com.specagent.agent.snapshot;

import com.specagent.agent.contract.AgentContracts;
import com.specagent.agent.contract.AgentInputSnapshot;
import com.specagent.capability.CapabilityAdapter;
import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.capability.SideEffectClass;
import com.specagent.answer.AnswerService;
import com.specagent.common.Hashes;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextOperationType;
import com.specagent.context.ContextSnapshot;
import com.specagent.graph.GraphCommandService;
import com.specagent.graph.NodeRelation;
import com.specagent.graph.NodeRelationType;
import com.specagent.node.Node;
import com.specagent.patch.AnswerPatchService;
import com.specagent.patch.Claim;
import com.specagent.patch.ClaimKind;
import com.specagent.patch.ClaimStatus;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Frozen ContextSnapshot integrity: once a ContextSnapshot has been projected
 * into the model-facing {@code AgentInputSnapshot}, retrying the SAME snapshot
 * must replay the exact same semantic model input — never a live rebuild over
 * mutable node bodies, related-node bodies, route labels, or capability
 * results. New snapshots may see the new live state; old ones never do.
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

                @Override
                public CapabilityResult invoke(CapabilityInvocation invocation) {
                    return new CapabilityResult(invocation.invocationId(),
                            invocation.invocationKey(), invocation.capabilityId(),
                            CapabilityResult.Status.SUCCEEDED,
                            Map.of("marker", invocation.invocationKey()),
                            List.of(), Map.of(), List.of());
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
    @Autowired private com.specagent.node.NodeService nodeService;

    /**
     * T4 — post-STATE_UPDATE distinct freeze: the pre-answer snapshot X and
     * the post-state snapshot Y are different identities with different frozen
     * projections, and the DECISION-side projection Y exposes the just
     * persisted patch claims — including an unresolved conflict claim, so
     * Conflict Intelligence keeps its causal visibility into the DECISION
     * input.
     */
    @Test
    void postStateSnapshotIsADistinctFreezeCarryingPersistedClaims() {
        Project project = projectService.createProject("冻结投影-后状态-" + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node question = nodeService.createRootNode(project.id(), routeId,
                "最重要的目标是什么？", null, List.of(), true);

        // Pre-answer snapshot X: the STATE_UPDATE-side context.
        ContextSnapshot preState = contextBuilder.buildFromActiveRoute(
                project.id(), UUID.randomUUID(), ContextOperationType.NORMAL);
        AgentInputSnapshot preProjection = snapshotBuilder.build(preState);

        // STATE_UPDATE checkpoint: persist the immutable answer and its patch,
        // including an unresolved conflict claim exactly as Conflict
        // Intelligence produces them.
        com.specagent.answer.Answer answer = answerService.finalizeAnswer(
                project.id(), routeId, question.id(), null, "A 目标优先于 B", "user");
        Claim conflict = Claim.of(ClaimKind.CONFLICT, "A 与 B 在同一时间窗内不能同时成立",
                ClaimStatus.UNRESOLVED, question.id(), answer.id());
        answerPatchService.save(project.id(), routeId, question.id(), answer.id(),
                List.of(conflict), null);

        // Post-state snapshot Y: a distinct identity that the DECISION call
        // must read; its frozen projection carries the persisted conflict.
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

        // Replaying X stays frozen: it never absorbs the post-state claims.
        assertThat(snapshotBuilder.build(preState)).isEqualTo(preProjection);
        assertThat(snapshotBuilder.build(preState).effectiveClaims())
                .noneSatisfy(claim -> assertThat(claim.kind()).isEqualTo("conflict"));
    }
    /**
     * T1 — same-ContextSnapshot reproducibility over a mutable LINEAGE node
     * body: the anchor of the query context is an editable user draft, so a
     * live rebuild would pick up the edited body. The frozen projection must
     * not.
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

        // A NEW snapshot may legitimately see the new live body.
        AgentInputSnapshot fresh = snapshotBuilder.build(contextBuilder.buildForNodeQuery(
                project.id(), routeId, draft.id(), "这个节点说了什么？"));
        assertThat(fresh.snapshotId()).isNotEqualTo(first.snapshotId());
        assertThat(fresh.lineage().get(0).node().body().text()).isEqualTo("after");
    }

    /**
     * T2 — related-node reproducibility: a route-bound NODE_QUERY freeze sees
     * the related Knowledge draft body "before"; editing the draft afterwards
     * must not change the replayed projection, while a new snapshot reads
     * "after".
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
     * T3 — capability observation reproducibility: results completed after a
     * snapshot was frozen must not retroactively appear in its replayed
     * projection; a new snapshot may include them.
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
        capabilityRuntime.invoke(key, "test.frozen-probe", project.id(), null, Map.of());

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
     * T6 — routeless NODE_QUERY stays frozen-safe: a floating node query
     * (routeId = null) freezes and replays without reintroducing any
     * route-required assumption.
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
     * Durable identity: exactly one frozen projection row per snapshot, bound
     * to the snapshot identity, canonical version, byte-stable payload and a
     * matching payload hash.
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
        // The frozen payload is the canonical contract projection and parses
        // back to the exact same semantic snapshot the builder returned.
        assertThat(AgentContracts.read(payload, AgentInputSnapshot.class)).isEqualTo(first);
        assertThat(payload).contains("\"snapshotId\":\"" + snapshot.id() + "\"");
    }

    /**
     * T8 — tamper/malformed persistence fails closed: hash mismatch, an
     * unsupported projection version, and malformed JSON must each raise a
     * typed corruption failure — never a silent live rebuild.
     */
    @Test
    void tamperedOrUnsupportedFrozenPayloadFailsClosed() {
        Project project = projectService.createProject("冻结投影-篡改-" + UUID.randomUUID());
        UUID routeId = project.activeRouteId();
        Node draft = graphCommandService.createRootDraftNode(project.id(), routeId,
                "NOTE", Map.of("text", "audit"));

        // 1. Hash mismatch (payload tampered after freeze).
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

        // 2. Unsupported projection version.
        ContextSnapshot versioned = contextBuilder.buildForNodeQuery(
                project.id(), routeId, draft.id(), "篡改-version");
        snapshotBuilder.build(versioned);
        jdbcTemplate.update(
                "UPDATE agent_input_projections SET projection_version = ? WHERE snapshot_id = ?",
                "agent-input-projection.v999", versioned.id());
        assertThatThrownBy(() -> snapshotBuilder.build(versioned))
                .isInstanceOf(FrozenProjectionCorruptedException.class)
                .hasMessageContaining("version");

        // 3. Malformed JSON payload (hash-consistent, so parse must fail).
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

        // A still-identical sibling snapshot keeps working after the failures.
        ContextSnapshot healthy = contextBuilder.buildForNodeQuery(
                project.id(), routeId, draft.id(), "健康检查");
        assertThat(snapshotBuilder.build(healthy).lineage().get(0).node().body().text())
                .isEqualTo("audit");
    }

    /**
     * Identity binding: a frozen row that belongs to a DIFFERENT snapshot
     * identity must be rejected, not replayed.
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
