package com.specagent.capability;

import com.specagent.agent.protocol.AgentInputSnapshot;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.workspace.context.ContextBuilder;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.graph.GraphCommandService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:CapabilityIntegrationTest.java
 *
 * 测试目标:验证能力基础设施的集成行为——带来源的截断资源抽取、幂等
 * 重放、快照中描述符按上下文相关性过滤、已完成调用作为观察进入后续周期。
 * 端到端回答周期覆盖见 CapabilityAnswerCycleIntegrationTest。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CapabilityIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService commandService;
    @Autowired private CapabilityRuntime capabilityRuntime;
    @Autowired private CapabilityInvocationRepository invocationRepository;
    @Autowired private CapabilityRegistry registry;
    @Autowired private AgentInputSnapshotBuilder snapshotBuilder;
    @Autowired private ContextBuilder contextBuilder;
    @Autowired private RouteRepository routeRepository;
    @Autowired private NodeService nodeService;

    private Project project;
    private Route route;
    private Node resource;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("能力基础测试");
        route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        resource = commandService.attachResource(
                project.id(), route.id(), null, "TEXT",
                Map.of("text", "需求背景：客户需要离线模式，" + "详细内容".repeat(600)));
    }

    @Test
    void extractTextReturnsBoundedExcerptWithProvenance() {
        CapabilityResult result = capabilityRuntime.invoke(
                "test-key-1", ResourceExtractTextCapability.CAPABILITY_ID,
                project.id(), null, Map.of("nodeRef", "node:" + resource.id()));

        assertThat(result.status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
        String excerpt = (String) result.content().get("excerpt");
        assertThat(excerpt.length()).isLessThanOrEqualTo(ResourceExtractTextCapability.MAX_EXCERPT_CHARS);
        assertThat(result.content().get("truncated")).isEqualTo(true);
        assertThat(result.sourceRefs()).containsExactly("node:" + resource.id());
        assertThat(result.provenance().get("kind")).isEqualTo("EXTERNAL_SOURCE_EVIDENCE");
    }

    @Test
    void replayWithSameInvocationKeyDoesNotReExecute() {
        capabilityRuntime.invoke("test-key-2", ResourceExtractTextCapability.CAPABILITY_ID,
                project.id(), null, Map.of("nodeRef", "node:" + resource.id()));

        CapabilityResult replay = capabilityRuntime.invoke(
                "test-key-2", ResourceExtractTextCapability.CAPABILITY_ID,
                project.id(), null, Map.of("nodeRef", "node:" + resource.id()));

        assertThat(replay.status()).isEqualTo(CapabilityResult.Status.REPLAYED);
        // 幂等性:只有一条持久化记录,绝不会二次执行。
        assertThat(invocationRepository.findByInvocationKey("test-key-2")).isPresent();
        assertThat(invocationRepository.findRecentCompleted(project.id(), 10))
                .filteredOn(record -> record.invocationKey().equals("test-key-2"))
                .hasSize(1);
    }

    @Test
    void unknownCapabilityIdFailsClosedAsTypedResult() {
        CapabilityResult result = capabilityRuntime.invoke(
                "test-key-3", "no.such.capability", project.id(), null, Map.of());

        assertThat(result.status()).isEqualTo(CapabilityResult.Status.FAILED);
        assertThat(String.valueOf(result.content().get("reason"))).contains("Unknown capability");
    }

    @Test
    void nonResourceNodeIsRejectedByExtractText() {
        Node knowledge = commandService.appendContinuation(
                project.id(), route.id(), resource.id(), "NOTE", Map.of("text", "想法")).node();

        CapabilityResult result = capabilityRuntime.invoke(
                "test-key-4", ResourceExtractTextCapability.CAPABILITY_ID,
                project.id(), null, Map.of("nodeRef", "node:" + knowledge.id()));

        assertThat(result.status()).isEqualTo(CapabilityResult.Status.FAILED);
        assertThat(String.valueOf(result.content().get("reason"))).contains("not a RESOURCE");
    }

    @Test
    void snapshotExposesResourceCapabilityOnlyForRelevantLineage() {
        // lineage 中存在 RESOURCE 节点时,该能力可见。
        ContextSnapshot withResource = contextBuilder.buildFromActiveRoute(
                project.id(), UUID.randomUUID(), ContextOperationType.NORMAL);
        AgentInputSnapshot snapshotWithResource = snapshotBuilder.build(withResource);
        assertThat(snapshotWithResource.availableCapabilities())
                .extracting(com.specagent.agent.protocol.CapabilityDescriptor::id)
                .contains(ResourceExtractTextCapability.CAPABILITY_ID);

        // 没有 RESOURCE 节点的项目仍然能看到工作区级别的记忆搜索能力,
        // 而资源专属能力因缺少 RESOURCE 上下文保持隐藏。
        Project plain = projectService.createProject("无资源项目");
        ContextSnapshot withoutResource = contextBuilder.buildFromActiveRoute(
                plain.id(), UUID.randomUUID(), ContextOperationType.NORMAL);
        AgentInputSnapshot snapshotWithout = snapshotBuilder.build(withoutResource);
        assertThat(snapshotWithout.availableCapabilities())
                .extracting(com.specagent.agent.protocol.CapabilityDescriptor::id)
                .contains(com.specagent.capability.MemorySearchCapability.CAPABILITY_ID)
                .doesNotContain(ResourceExtractTextCapability.CAPABILITY_ID);
    }

    @Test
    void completedInvocationsEnterLaterSnapshotsAsObservations() {
        capabilityRuntime.invoke("test-key-5", ResourceExtractTextCapability.CAPABILITY_ID,
                project.id(), null, Map.of("nodeRef", "node:" + resource.id()));

        ContextSnapshot snapshot = contextBuilder.buildFromActiveRoute(
                project.id(), UUID.randomUUID(), ContextOperationType.NORMAL);
        AgentInputSnapshot built = snapshotBuilder.build(snapshot);

        assertThat(built.capabilityResults()).isNotEmpty();
        assertThat(built.capabilityResults().get(0).capabilityId())
                .isEqualTo(ResourceExtractTextCapability.CAPABILITY_ID);
        assertThat(built.capabilityResults().get(0).sourceRefs())
                .contains("node:" + resource.id());
    }

    @Test
    void resourceCannotBeAttachedAtHistoricalNode() {
        Node tip = commandService.appendContinuation(
                project.id(), route.id(), resource.id(), "NOTE", Map.of("text", "tip")).node();

        assertThatThrownBy(() -> commandService.attachResource(
                project.id(), route.id(), resource.id(), "URL", Map.of("url", "https://x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("current tip");
    }

    @Test
    void registryHoldsTheResourceCapabilityWithReadOnlyNoneClassification() {
        CapabilityDescriptor descriptor = registry
                .findDescriptor(ResourceExtractTextCapability.CAPABILITY_ID)
                .orElseThrow();
        assertThat(descriptor.readOnly()).isTrue();
        assertThat(descriptor.sideEffectClass()).isEqualTo(SideEffectClass.NONE);
        assertThat(descriptor.supports()).anySatisfy(support -> support.startsWith("RESOURCE:"));
    }
}
