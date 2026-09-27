package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.DecisionCycleTestDriver;
import com.specagent.agent.protocol.AgentArtifactResponse;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.workspace.context.ContextSnapshotRepository;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;

/**
 * 文件名:ArtifactRouteBindingIntegrationTest.java
 *
 * 测试目标:P1 路由绑定回归覆盖:工件 run 从快照构建到持久化都绑定其入队时的
 * 路由/tip。run 排队期间切换活动路由会使 run fail closed(不产出快照);成功 run 的
 * 上下文与规格快照始终精确携带 run 自身的路由身份与 tip。
 */
@SpringBootTest
@ActiveProfiles("test")
class ArtifactRouteBindingIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private RunService runService;
    @Autowired
    private RunWorker worker;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private ContextSnapshotRepository contextSnapshotRepository;
    @Autowired
    private com.specagent.workspace.spec.SpecSnapshotRepository specSnapshotRepository;
    @Autowired
    private com.specagent.workspace.answer.AnswerService answerService;
    @Autowired
    private com.specagent.workspace.patch.AnswerPatchService answerPatchService;
    @SpyBean
    private AgentDecisionEngine decisionEngine;

    private Project seededProject() {
        Project project = projectService.createProject("Route binding " + UUID.randomUUID());
        var root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Root?", null, List.of(), true);
        // fork 要求分支点上有已定稿的答案。种子答案携带其 STATE_UPDATE 检查点,
        // 因为答案未完成状态更新的路由 tip 会被工件门禁拒绝(见
        // ArtifactGenerationAnswerGateIntegrationTest)——本 fixture 关注的是路由
        // 绑定,而不是那个门禁。
        var seedAnswer = answerService.finalizeAnswer(project.id(), project.activeRouteId(),
                root.id(), null, "seed answer", "user");
        answerPatchService.save(project.id(), project.activeRouteId(), root.id(),
                seedAnswer.id(), List.of(new com.specagent.workspace.patch.Claim(null,
                        com.specagent.workspace.patch.ClaimKind.fromCode("goal"), "seed claim",
                        com.specagent.workspace.patch.ClaimStatus.fromCode("confirmed"), 0.9,
                        root.id(), seedAnswer.id())), null);
        return project;
    }

    @Test
    void artifactRunFailsClosedWhenActiveRouteChangesBeforeExecution() {
        Project project = seededProject();
        UUID routeA = project.activeRouteId();
        UUID tipA = routeService.getRoute(routeA).orElseThrow().tipNodeId();

        UUID runId = runService.createQueuedArtifactGeneration(project.id()).id();

        // 用户在工件 run 等待期间切换了活动路由。
        var routeB = routeService.forkFromNode(project.id(), routeA, tipA, "switched");
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(routeB.id());

        stubSuccessfulArtifact();
        var claimed = runService.claimNextArtifact()
                .filter(run -> run.id().equals(runId))
                .orElseThrow();
        assertThatThrownBy(() -> worker.executeRun(claimed))
                .isInstanceOf(StaleRunTargetException.class)
                .hasMessageContaining("Active route changed");

        assertThat(agentRunService.getRun(runId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.FAILED);
        assertThat(specSnapshotRepository.findByRoute(routeA)).isEmpty();
        assertThat(specSnapshotRepository.findByRoute(routeB.id())).isEmpty();
    }

    @Test
    void artifactSnapshotIsBoundToRunRoute() {
        Project project = seededProject();
        UUID routeId = project.activeRouteId();
        UUID inputNodeId = routeService.getRoute(routeId).orElseThrow().tipNodeId();

        UUID runId = runService.createQueuedArtifactGeneration(project.id()).id();
        stubSuccessfulArtifact();

        // 即使在入队与领取之间有其他路由变为活动,持久化的上下文/快照
        // 也必须绑定到 run 自己的路由。
        var claimed = runService.claimNextArtifact()
                .filter(run -> run.id().equals(runId))
                .orElseThrow();
        worker.executeRun(claimed);

        assertThat(agentRunService.getRun(runId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);
        var run = agentRunService.getRun(runId).orElseThrow();
        var contextSnapshot = contextSnapshotRepository.findById(run.contextSnapshotId()).orElseThrow();
        var specSnapshot = specSnapshotRepository.findById(run.producedSpecSnapshotId()).orElseThrow();

        assertThat(run.routeId()).isEqualTo(routeId);
        assertThat(contextSnapshot.routeId()).isEqualTo(run.routeId());
        assertThat(contextSnapshot.tipNodeId()).isEqualTo(inputNodeId);
        assertThat(specSnapshot.routeId()).isEqualTo(run.routeId());
        assertThat(specSnapshot.tipNodeId()).isEqualTo(inputNodeId);
        assertThat(specSnapshot.contextSnapshotId()).isEqualTo(contextSnapshot.id());
    }

    @Test
    void specSourceGuardRejectsRouteSnapshotMismatch() {
        // 守卫级别的直接证明:路由 A + 路由 B 的快照必须在逐引用校验之前
        // 被拒绝,即使每个引用本都能解析。
        var guard = new com.specagent.agent.gates.SpecSourceReferenceGuard(
                org.mockito.Mockito.mock(com.specagent.workspace.route.RouteRepository.class),
                org.mockito.Mockito.mock(com.specagent.workspace.node.NodeRepository.class),
                org.mockito.Mockito.mock(com.specagent.workspace.answer.AnswerRepository.class),
                org.mockito.Mockito.mock(com.specagent.workspace.patch.AnswerPatchRepository.class),
                org.mockito.Mockito.mock(com.specagent.workspace.route.RouteHistoryResolver.class));

        UUID projectId = UUID.randomUUID();
        UUID routeA = UUID.randomUUID();
        UUID routeB = UUID.randomUUID();
        var snapshotOfB = new com.specagent.workspace.context.ContextSnapshot(
                UUID.randomUUID(), projectId, routeB, UUID.randomUUID(),
                com.specagent.workspace.context.ContextOperationType.NORMAL,
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), null, "hash",
                java.time.Instant.now());

        // 该引用本身甚至是一个真实的 NODE 引用,对 snapshotOfB 能通过逐引用
        // 检查——只有路由配对是错的。
        var result = guard.validate(projectId, routeA, snapshotOfB,
                List.of(com.specagent.workspace.spec.SourceReference.of(
                        com.specagent.workspace.spec.SourceKind.CONTEXT, snapshotOfB.id())));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors())
                .anyMatch(error -> error.contains("does not belong to route " + routeA));
        assertThat(result.errors()).anyMatch(error -> error.contains("does not belong to route"));
    }

    private void stubSuccessfulArtifact() {
        org.mockito.Mockito.doAnswer(invocation -> {
            var envelope = (AgentRequestEnvelope) invocation.getArgument(0);
            return new AgentArtifactResponse(
                com.specagent.agent.protocol.AgentProtocol.ARTIFACT_PROTOCOL_VERSION,
                envelope.runId(),
                new AgentArtifactResponse.ArtifactGenerationResult(
                        "spec_snapshot",
                        List.of(new AgentArtifactResponse.ArtifactSection(
                                "Overview", "grounded content",
                                List.of("route:" + envelope.snapshot().routeId()))),
                        List.of()),
                new com.specagent.agent.protocol.UsageView(1, List.of()));
        })
                .when(decisionEngine).runArtifactGeneration(any(AgentRequestEnvelope.class));
    }
}
