package com.specagent.workspace.graph;

import com.specagent.workspace.answer.AnswerService;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeOption;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:GraphLineageInvariantIntegrationTest.java
 *
 * 测试目标:写入时的谱系不变量——未回答的 Question 不允许获得谱系
 * (链式)子节点(知识/资源可以作为溯源挂在它下面,但在回答之前它必须
 * 一直是路线 tip);谱系必须无环;{@code sourceRouteId} 祖先链也必须无环。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GraphLineageInvariantIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private Project newProject(String title) {
        return projectService.createProject(title);
    }

    @Test
    void appendContinuationFromUnansweredQuestionRejects() throws Exception {
        Project project = newProject("Unanswered continuation");
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "未答问题", "P0",
                List.of(NodeOption.of("A", "a")), true);

        mockMvc.perform(post("/api/v1/projects/{pid}/nodes/{nid}/continuation",
                        project.id(), root.id())
                        .contentType(APPLICATION_JSON)
                        .content("{\"routeId\":\"" + routeId + "\"," +
                                "\"subtype\":\"NOTE\",\"content\":{\"text\":\"cross\"}}"))
                .andExpect(status().isConflict());
        // 路线 tip 未变:没有任何操作越过未回答的 Question。
        assertThat(routeService.getRoute(routeId).orElseThrow().tipNodeId())
                .isEqualTo(root.id());
    }

    @Test
    void attachResourceFromUnansweredQuestionRejectsWhenItAdvancesLineage() throws Exception {
        Project project = newProject("Unanswered resource");
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "未答问题", "P0",
                List.of(NodeOption.of("A", "a")), true);

        // RESOURCE 可以挂在未回答的问题 tip 上(按节点类型的规则),但只能
        // 作为溯源子节点:问题仍是路线 tip,并保留其回答输入。
        mockMvc.perform(post("/api/v1/projects/{pid}/resources", project.id())
                        .contentType(APPLICATION_JSON)
                        .content("{\"routeId\":\"" + routeId + "\"," +
                                "\"parentNodeId\":\"" + root.id() + "\"," +
                                "\"subtype\":\"TEXT\",\"content\":{\"text\":\"attachment\"}}"))
                .andExpect(status().isCreated());

        Route route = routeService.getRoute(routeId).orElseThrow();
        assertThat(route.tipNodeId()).isEqualTo(root.id());
    }

    @Test
    void answeredQuestionCanAdvance() throws Exception {
        Project project = newProject("Answered advance");
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "已答问题", "P0",
                List.of(NodeOption.of("A", "a")), true);
        var answer = answerService.finalizeAnswer(project.id(), routeId, root.id(), null, "answer", "user");
        answerPatchService.save(project.id(), routeId, root.id(), answer.id(), List.of(), null);

        mockMvc.perform(post("/api/v1/projects/{pid}/nodes/{nid}/continuation",
                        project.id(), root.id())
                        .contentType(APPLICATION_JSON)
                        .content("{\"routeId\":\"" + routeId + "\"," +
                                "\"subtype\":\"NOTE\",\"content\":{\"text\":\"go\"}}"))
                .andExpect(status().isCreated());
    }

    @Test
    void forkFromUnansweredQuestionRejects() throws Exception {
        Project project = newProject("Unanswered fork");
        UUID routeId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), routeId, "未答问题", "P0",
                List.of(NodeOption.of("A", "a")), true);

        mockMvc.perform(post("/api/v1/projects/{pid}/nodes/{nid}/fork", project.id(), root.id())
                        .contentType(APPLICATION_JSON)
                        .content("{\"sourceRouteId\":\"" + routeId + "\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void lineageAncestorCycleIsRejectedAtWriteTime() throws Exception {
        Project project = newProject("Lineage cycle");
        UUID routeId = project.activeRouteId();
        Node a = nodeService.createRootNode(project.id(), routeId, "A", null,
                List.of(), true);
        Node b = nodeService.createChildNode(project.id(), routeId, a.id(), "B", null,
                List.of(), true);
        Node c = nodeService.createChildNode(project.id(), routeId, b.id(), "C", null,
                List.of(), true);
        answerService.finalizeAnswer(project.id(), routeId, a.id(), null, "a answer", "user");
        answerService.finalizeAnswer(project.id(), routeId, b.id(), null, "b answer", "user");
        answerService.finalizeAnswer(project.id(), routeId, c.id(), null, "c answer", "user");

        // 构造脏数据:A -> B -> C -> A。任何在损坏谱系上推进谱系的命令都必须
        // 快速失败(fail closed),而不是默默继续。
        jdbc.update("UPDATE nodes SET parent_node_id = :parent WHERE id = :id",
                Map.of("parent", c.id(), "id", a.id()));

        mockMvc.perform(post("/api/v1/projects/{pid}/nodes/{nid}/continuation",
                        project.id(), c.id())
                        .contentType(APPLICATION_JSON)
                        .content("{\"routeId\":\"" + routeId + "\"," +
                                "\"subtype\":\"NOTE\",\"content\":{\"text\":\"x\"}}"))
                .andExpect(status().isConflict());
    }

    @Test
    void routeProvenanceCycleIsRejectedWhenCreatingBranch() throws Exception {
        Project project = newProject("Provenance cycle");
        UUID routeId = project.activeRouteId();
        nodeService.createRootNode(project.id(), routeId, "根", null, List.of(), true);

        Route sibling = routeService.createRoute(project.id(),
                com.specagent.workspace.route.RouteLifecycleStatus.OPEN, "sibling");
        Node siblingRoot = nodeService.createRootNode(
                project.id(), sibling.id(), "sibling 节点", null, List.of(), true);
        answerService.finalizeAnswer(project.id(), sibling.id(), siblingRoot.id(),
                null, "sibling answer", "user");

        // 构造脏数据:sourceRouteId 祖先链成环
        // (sibling -> routeId -> sibling)。
        jdbc.update("UPDATE routes SET source_route_id = :source WHERE id = :id",
                Map.of("source", routeId, "id", sibling.id()));
        jdbc.update("UPDATE routes SET source_route_id = :source WHERE id = :id",
                Map.of("source", sibling.id(), "id", routeId));

        // 在损坏的 sibling 上执行 fork 必须在任何写入之前被拒绝。
        assertThatThrownBy(() -> routeService.forkFromNode(
                project.id(), sibling.id(), siblingRoot.id(), "cycle fork"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ROUTE_PROVENANCE_CYCLE");
    }
}
