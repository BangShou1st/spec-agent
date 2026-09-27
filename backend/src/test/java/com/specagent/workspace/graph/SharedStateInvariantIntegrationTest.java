package com.specagent.workspace.graph;

import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerRepository;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeOption;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteHistoryResolver;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:SharedStateInvariantIntegrationTest.java
 *
 * 测试目标:最终产品模型下的共享状态 / Answer 身份不变量——项目内一个
 * 权威 Question 节点只携带一个不可变的 Answer 身份。分支路线通过继承引用
 * 指向同一个 Answer id;同一权威节点出现第二个不同的 Answer 即是
 * SHARED_STATE_DIVERGENCE 不变量违反,绝不是一种正常的 UI 状态。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SharedStateInvariantIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AnswerRepository answerRepository;
    @Autowired
    private RouteService routeService;
    @Autowired
    private RouteHistoryResolver routeHistoryResolver;

    private Project newProject(String title) {
        return projectService.createProject(title);
    }

    @Test
    void sameQuestionNodeCannotGainASecondDistinctAnswerOnAnotherRoute() {
        Project project = newProject("Shared state project");
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "共享问题", "P0",
                List.of(NodeOption.of("A", "a")), true);
        answerService.finalizeAnswer(project.id(), routeId, root.id(),
                null, "source answer", "user");

        // fork 共享权威节点;fork 路线通过继承引用携带同一个 answer id,
        // 因此不允许再 finalize 一个新的。
        Route fork = routeService.forkFromNode(project.id(), routeId, root.id(), "共享 fork");

        assertThatThrownBy(() -> answerService.finalizeAnswer(
                project.id(), fork.id(), root.id(), null, "divergent answer", "user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHARED_STATE_DIVERGENCE");
    }

    @Test
    void inheritedRouteReferencesTheSameAnswerIdentity() {
        Project project = newProject("Shared identity project");
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "共享问题", "P0",
                List.of(NodeOption.of("A", "a")), true);
        Answer source = answerService.finalizeAnswer(project.id(), routeId, root.id(),
                null, "source answer", "user");

        Route fork = routeService.forkFromNode(project.id(), routeId, root.id(), "共享 fork");
        List<Answer> effective = routeHistoryResolver.resolveEffectiveAnswers(
                fork.id(), List.of(root.id()));
        assertThat(effective).hasSize(1);
        assertThat(effective.get(0).id()).isEqualTo(source.id());
        assertThat(effective.get(0).nodeId()).isEqualTo(root.id());
    }

    @Test
    void readModelFailsClosedWhenTwoDistinctAnswerIdsResolveToSameNode() throws Exception {
        Project project = newProject("Read model divergence project");
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "共享问题", "P0",
                List.of(NodeOption.of("A", "a")), true);
        answerService.finalizeAnswer(project.id(), routeId, root.id(),
                null, "first answer", "user");

        // fork 路线通过继承引用共享权威节点。刻意注入脏数据:绕过写入时
        // 不变量,在 fork 路线上为同一个权威节点插入第二个不同的 Answer,
        // 以证明读取模型能检测到该损坏,而不是把它当作正常 UI 状态展示。
        Route fork = routeService.forkFromNode(project.id(), routeId, root.id(), "corrupt fork");
        Answer second = new Answer(UUID.randomUUID(), project.id(), fork.id(), root.id(),
                null, "second answer", "user", java.time.Instant.now());
        answerRepository.save(second);

        mockMvc.perform(get("/api/v1/projects/{projectId}/graph", project.id()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_INVARIANT_VIOLATION"));
    }

    @Test
    void reanswerKeepsReadModelSingleAnswerIdentity() throws Exception {
        Project project = newProject("Reanswer shared project");
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "问题", "P0",
                List.of(NodeOption.of("A", "a")), true);
        answerService.finalizeAnswer(project.id(), routeId, root.id(), null, "answer", "user");

        // 重新回答会创建新的 Question 节点;旧节点不再位于 re-answer 路线的
        // 谱系上,因此读取模型对每个权威节点仍只解析出一个 Answer 身份。
        Route reanswer = routeService.reanswerFromNode(project.id(), routeId, root.id(), "retry");
        assertThat(nodeService.getNode(reanswer.tipNodeId()).orElseThrow().id())
                .isNotEqualTo(root.id());

        mockMvc.perform(get("/api/v1/projects/{projectId}/graph", project.id()))
                .andExpect(status().isOk());
    }
}