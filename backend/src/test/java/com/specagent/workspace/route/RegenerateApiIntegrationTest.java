package com.specagent.workspace.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.runtime.AgentRunStatus;
import com.specagent.agent.AnswerCycleTestDriver;
import com.specagent.agent.DecisionCycleTestDriver;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import com.specagent.common.Ids;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.context.ContextSnapshotRepository;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:RegenerateApiIntegrationTest.java
 *
 * 测试目标:重新生成(replacement cutover)的集成测试——
 * {@code POST /agent-runs} 且 {@code operation=REGENERATE_NODE} 时返回
 * 202 + runId,worker 为替换内容执行一次 DECISION,确定性的运行时提交保持
 * 冻结拓扑语义:旧路线 SUPERSEDED、替换路线 OPEN 且活跃、替换节点取代
 * 目标节点,冻结的 regenerate 上下文排除目标自身的回答/补丁/子树。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RegenerateApiIntegrationTest {

    private static final String OLD_ANSWER_SENTINEL = "REGEN_OLD_ANSWER_DO_NOT_LEAK_3c1e";
    /** 确定性假 DECISION 总是提出这个替换内容。 */
    private static final String FAKE_REPLACEMENT_QUESTION =
            "A sharper version of the rejected question.";
    private static final String REGEN_INSTRUCTION =
            "Ask this in a more specific way about operators";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private DecisionCycleTestDriver draftDriver;
    @Autowired
    private AnswerCycleTestDriver answerDriver;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private RunService runService;
    @Autowired
    private RunWorker worker;
    @Autowired
    private ContextSnapshotRepository contextSnapshotRepository;

    private record RegenerateSetup(Project project, Node root, Node target, Node targetChild,
                                   UUID sourceRouteId, UUID oldAnswerId, UUID oldPatchId) {
    }

    /**
     * 构建 root -> 已回答谱系,其中 regenerate 目标携带自己独立的问题,
     * 以便确定性替换内容可以与它不同(否则重复问题守卫会拒绝假内容)。
     */
    private RegenerateSetup buildLineageWithAnsweredTarget() {
        Project project = projectService.createProject("Regenerate project");
        var draftRun = draftDriver.draftQuestion(project.id());
        Node root = nodeService.getNode(draftRun.producedNodeId()).orElseThrow();
        answerDriver.submitFreeText(project.id(), "Root answer stays");
        var second = answerDriver.submitFreeText(project.id(), "Second answer stays");
        // 手动追加一个问题,让目标节点带有非假内容。
        UUID activeRouteId = second.run().routeId();
        Node targetParent = nodeService.getNode(second.producedNodeId()).orElseThrow();
        Node target = nodeService.createChildNode(
                project.id(), activeRouteId, targetParent.id(),
                "A sharper original question", null, java.util.List.of(), true);
        var targetAnswer = answerDriver.submitFreeText(
                project.id(), OLD_ANSWER_SENTINEL + " the replaced answer");
        Node targetChild = nodeService.getNode(targetAnswer.producedNodeId()).orElseThrow();
        return new RegenerateSetup(project, root, target, targetChild,
                activeRouteId, targetAnswer.answerId(), targetAnswer.patchId());
    }

    /** 通过真实 API 面把替换任务入队;返回 runId。 */
    private String enqueueRegenerate(Project project, UUID nodeId,
                                     UUID sourceRouteId, String instruction) throws Exception {
        MvcResult created = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", project.id())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"operation": "REGENERATE_NODE", "nodeId": "%s",
                                         "sourceRouteId": "%s", "freeText": "%s"}
                                        """.formatted(nodeId, sourceRouteId, instruction)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation").value("REGENERATE_NODE"))
                .andExpect(jsonPath("$.phase").value("CREATED"))
                .andReturn();
        return new ObjectMapper()
                .readTree(created.getResponse().getContentAsString()).get("runId").asText();
    }

    private AgentRun executeQueued(String runId) {
        UUID enqueuedId = UUID.fromString(runId);
        var claimed = runService.claimNextRegenerate()
                .filter(run -> run.id().equals(enqueuedId))
                .orElseThrow(() -> new IllegalStateException(
                        "Expected queued regenerate run " + runId));
        worker.executeRun(claimed);
        return agentRunService.getRun(enqueuedId).orElseThrow();
    }

    @Test
    void regenerateSucceedsWithFrozenSemanticsAndIsolation() throws Exception {
        RegenerateSetup s = buildLineageWithAnsweredTarget();

        String runId = enqueueRegenerate(s.project(), s.target().id(),
                s.sourceRouteId(), REGEN_INSTRUCTION);

        // 命令在任何模型工作发生之前就已返回 202。
        assertThat(agentRunService.getRun(UUID.fromString(runId)).orElseThrow().status())
                .isEqualTo(AgentRunStatus.CREATED);

        AgentRun done = executeQueued(runId);

        assertThat(done.status()).isEqualTo(AgentRunStatus.COMPLETED);

        Route replacementRoute = routeService.listRoutes(s.project().id()).stream()
                .filter(r -> r.id().equals(
                        projectService.getProject(s.project().id()).orElseThrow().activeRouteId()))
                .findFirst()
                .orElseThrow();
        assertThat(replacementRoute.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        Route oldRoute = routeService.getRoute(s.sourceRouteId()).orElseThrow();
        assertThat(oldRoute.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.SUPERSEDED);

        // 替换节点携带确定性提案内容,以及运行时所有的拓扑身份。
        Node replacement = nodeService.getNode(done.producedNodeId()).orElseThrow();
        assertThat(replacement.question()).isEqualTo(FAKE_REPLACEMENT_QUESTION);
        assertThat(replacement.supersedesNodeId()).isEqualTo(s.target().id());
        assertThat(replacement.options()).hasSize(1);
        assertThat(replacement.options().get(0).label()).isEqualTo("Clarify the primary goal");

        // 冻结的 regenerate 上下文:只包含父谱系。
        ContextSnapshot regen = contextSnapshotRepository
                .findByRoute(replacementRoute.id()).stream()
                .filter(snapshot -> snapshot.operationType() == ContextOperationType.REGENERATE)
                .findFirst()
                .orElseThrow();
        assertThat(regen.includedNodeIds())
                .contains(s.root().id())
                .doesNotContain(s.target().id())
                .doesNotContain(s.targetChild().id());
        assertThat(regen.includedAnswerIds())
                .doesNotContain(s.oldAnswerId());
        assertThat(regen.includedPatchIds())
                .doesNotContain(s.oldPatchId());
        assertThat(regen.specialInputs())
                .contains(REGEN_INSTRUCTION)
                .contains(s.target().question())
                .doesNotContain(OLD_ANSWER_SENTINEL);
    }

    @Test
    void regenerateUnknownNodeRejected() throws Exception {
        Project project = projectService.createProject("Regenerate unknown node");

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operation": "REGENERATE_NODE", "nodeId": "%s",
                                 "sourceRouteId": "%s", "freeText": "x"}
                                """.formatted(Ids.random(), project.activeRouteId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NODE_NOT_FOUND"));
    }

    @Test
    void regenerateNodeFromAnotherProjectRejected() throws Exception {
        Project projectA = projectService.createProject("Regenerate owner A");
        Project projectB = projectService.createProject("Regenerate owner B");
        var draftRun = draftDriver.draftQuestion(projectA.id());
        Node nodeA = nodeService.getNode(draftRun.producedNodeId()).orElseThrow();

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", projectB.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operation": "REGENERATE_NODE", "nodeId": "%s",
                                 "sourceRouteId": "%s", "freeText": "x"}
                                """.formatted(nodeA.id(), projectB.activeRouteId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NODE_NOT_FOUND"));
    }

    @Test
    void regenerateRootNodeRemainsUnsupported() throws Exception {
        Project project = projectService.createProject("Regenerate root project");
        var draftRun = draftDriver.draftQuestion(project.id());
        Node root = nodeService.getNode(draftRun.producedNodeId()).orElseThrow();
        assertThat(root.isRoot()).isTrue();

        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"operation": "REGENERATE_NODE", "nodeId": "%s",
                                 "sourceRouteId": "%s", "freeText": "x"}
                                """.formatted(root.id(), project.activeRouteId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REGENERATE_ROOT_NOT_SUPPORTED"));
    }
}
