package com.specagent.workspace.route;

import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerRepository;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeRepository;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatch;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:RouteForkApiIntegrationTest.java
 *
 * 测试目标:fork 命令的集成测试——fork 保持"历史谱系视图"语义:不拷贝
 * 节点/回答/补丁,原路线不被触碰,新路线成为活跃路线。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RouteForkApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private com.specagent.agent.DecisionCycleTestDriver draftDriver;
    @Autowired
    private com.specagent.agent.AnswerCycleTestDriver answerDriver;
    @Autowired
    private NodeRepository nodeRepository;
    @Autowired
    private AnswerRepository answerRepository;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private AnswerService answerService;

    @Test
    void forkFromActiveLineageCreatesActiveHistoricalView() throws Exception {
        Project project = projectService.createProject("Fork project");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Root question", null, List.of(), true);
        Node child = nodeService.createChildNode(project.id(), project.activeRouteId(), root.id(),
                "Child question", null, List.of(), true);
        UUID originalRouteId = project.activeRouteId();
        answerService.finalizeAnswer(project.id(), originalRouteId, root.id(), null,
                "Root answer", "user");
        int nodeCountBefore = nodeRepository.findByProject(project.id()).size();

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/fork", project.id(), root.id())
                        .contentType(APPLICATION_JSON)
                        .content("{\"sourceRouteId\": \"" + originalRouteId
                                + "\", \"label\": \"Alternative route\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route.label").value("Alternative route"))
                .andExpect(jsonPath("$.route.lifecycleStatus").value("open"))
                .andExpect(jsonPath("$.route.isActive").value(true))
                .andExpect(jsonPath("$.route.rootNodeId").value(root.id().toString()))
                .andExpect(jsonPath("$.route.tipNodeId").value(root.id().toString()))
                .andExpect(jsonPath("$.route.createdFromNodeId").value(root.id().toString()));

        Route fork = routeService.listRoutes(project.id()).stream()
                .filter(r -> !r.id().equals(originalRouteId))
                .findFirst()
                .orElseThrow();

        // fork 语义:tip = 源节点,root = 源路线根节点,旧路线不变。
        assertThat(fork.tipNodeId()).isEqualTo(root.id());
        assertThat(fork.rootNodeId()).isEqualTo(root.id());
        assertThat(fork.createdFromNodeId()).isEqualTo(root.id());
        assertThat(fork.lifecycleStatus()).isEqualTo(RouteLifecycleStatus.OPEN);
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(fork.id());

        Route oldRoute = routeService.getRoute(originalRouteId).orElseThrow();
        assertThat(oldRoute.tipNodeId()).isEqualTo(child.id());
        assertThat(nodeRepository.findByProject(project.id())).hasSize(nodeCountBefore);
        // fork 路线上不拷贝任何回答或补丁。
        assertThat(answerRepository.findByRouteAndNodeIds(fork.id(), List.of(root.id()))).isEmpty();
        assertThat(answerPatchService.findByRoute(fork.id())).isEmpty();
    }

    @Test
    void forkDoesNotCopyAnswersOrPatchesFromSourceRoute() throws Exception {
        Project project = projectService.createProject("Fork copy check");
        var draftRun = draftDriver.draftQuestion(project.id());
        Node root = nodeService.getNode(draftRun.producedNodeId()).orElseThrow();
        UUID sourceRouteId = project.activeRouteId();
        var answerResult = answerDriver.submitFreeText(project.id(), "Root answer content");
        Answer answer = answerService.getAnswer(answerResult.answerId()).orElseThrow();
        AnswerPatch patch = answerPatchService.getPatch(answerResult.patchId()).orElseThrow();
        assertThat(answer.routeId()).isEqualTo(sourceRouteId);
        assertThat(patch.routeId()).isEqualTo(sourceRouteId);

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/fork", project.id(), root.id())
                        .contentType(APPLICATION_JSON)
                        .content("{\"sourceRouteId\": \"" + sourceRouteId + "\"}"))
                .andExpect(status().isOk());

        UUID forkRouteId = projectService.getProject(project.id()).orElseThrow().activeRouteId();
        Route fork = routeService.getRoute(forkRouteId).orElseThrow();
        assertThat(fork.tipNodeId()).isEqualTo(root.id());
        // 源回答/补丁留在源路线上,不在 fork 上。
        assertThat(answerRepository.findByRouteAndNodeIds(fork.id(), List.of(root.id()))).isEmpty();
        assertThat(answerPatchService.findByRoute(fork.id())).isEmpty();
        assertThat(answerService.getAnswer(answer.id()).orElseThrow().routeId())
                .isEqualTo(sourceRouteId);
    }

    @Test
    void forkUnknownNodeRejected() throws Exception {
        Project project = projectService.createProject("Fork unknown node");

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/fork", project.id(),
                        UUID.randomUUID())
                        .contentType(APPLICATION_JSON)
                        .content("{\"sourceRouteId\": \"" + project.activeRouteId() + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NODE_NOT_FOUND"));
    }

    @Test
    void forkNodeFromAnotherProjectRejected() throws Exception {
        Project projectA = projectService.createProject("Fork owner A");
        Project projectB = projectService.createProject("Fork owner B");
        Node nodeA = nodeService.createRootNode(projectA.id(), projectA.activeRouteId(),
                "A node", null, List.of(), true);

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/fork", projectB.id(), nodeA.id())
                        .contentType(APPLICATION_JSON)
                        .content("{\"sourceRouteId\": \"" + projectB.activeRouteId() + "\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NODE_NOT_FOUND"));
    }

    @Test
    void forkIsolationSiblingContentNotImported() throws Exception {
        Project project = projectService.createProject("Fork isolation");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Shared root", null, List.of(), true);
        Node siblingA = nodeService.createChildNode(project.id(), project.activeRouteId(), root.id(),
                "Sibling branch question", null, List.of(), true);
        UUID sourceRouteId = project.activeRouteId();
        var answer = answerService.finalizeAnswer(project.id(), sourceRouteId, root.id(), null,
                "Root answer", "user");
        answerPatchService.save(project.id(), sourceRouteId, root.id(), answer.id(), List.of(), null);

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/fork", project.id(), root.id())
                        .contentType(APPLICATION_JSON)
                        .content("{\"sourceRouteId\": \"" + sourceRouteId
                                + "\", \"label\": \"Root fork\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.route.tipNodeId").value(root.id().toString()));

        Route fork = routeService.getRoute(
                projectService.getProject(project.id()).orElseThrow().activeRouteId()).orElseThrow();
        assertThat(fork.tipNodeId()).isEqualTo(root.id());
        // 兄弟节点仍然存在,但不是 fork 历史的一部分。
        assertThat(nodeRepository.findById(siblingA.id())).isPresent();
        assertThat(fork.tipNodeId()).isNotEqualTo(siblingA.id());
    }

    /**
     * fork 路线的 tip 通过继承引用被回答,而非拥有自己的 Answer 记录。
     * DRAFT_QUESTION 预检查必须读取有效回答(自有 + 继承),否则在刚 fork 的
     * 路线上以 Follower 模式起草会被错误地以 UNANSWERED_QUESTION_HAS_CHILD
     * 拒绝,UI 随之退化为"分支已创建,第一个问题生成失败"。
     */
    @Test
    void draftOnForkedRouteAcceptsInheritedTipAnswer() throws Exception {
        Project project = projectService.createProject("Fork draft project");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Root question", null, List.of(), true);
        UUID sourceRouteId = project.activeRouteId();
        var answer = answerService.finalizeAnswer(project.id(), sourceRouteId, root.id(), null,
                "Root answer", "user");
        answerPatchService.save(project.id(), sourceRouteId, root.id(), answer.id(), List.of(), null);

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/fork", project.id(), root.id())
                        .contentType(APPLICATION_JSON)
                        .content("{\"sourceRouteId\": \"" + sourceRouteId
                                + "\", \"label\": \"Forked draft\"}"))
                .andExpect(status().isOk());

        Route fork = routeService.getRoute(
                projectService.getProject(project.id()).orElseThrow().activeRouteId()).orElseThrow();
        assertThat(fork.tipNodeId()).isEqualTo(root.id());
        // 仅有继承回答:fork 路线对它的 tip 不拥有任何 Answer 记录。
        assertThat(answerRepository.findByRouteAndNodeIds(fork.id(), List.of(root.id()))).isEmpty();

        // 不传 sourceRouteId:目标路线是 Active 指针(即 fork)。
        mockMvc.perform(post("/api/v1/projects/{projectId}/agent-runs", project.id())
                        .contentType(APPLICATION_JSON)
                        .content("{\"operation\": \"DRAFT_QUESTION\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.operation").value("DRAFT_QUESTION"));
    }
}
