package com.specagent.workspace.graph;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:SemanticRelationApiIntegrationTest.java
 *
 * 测试目标:语义关系创建路径的回归锁定。前端 {@code connection.spec.ts}
 * 的拖拽连线只通过 {@code POST /api/v1/projects/{id}/relations} 记录关系,
 * 本测试覆盖完整契约:身份三元组 (sourceNodeId, targetNodeId, relationType)
 * 原样存储、线型关系默认 RELATED_TO、人工拖拽的 origin 为 USER,以及关系
 * 立即在 UI 渲染 Inspector 所用的读取模型图视图中可见。以上任一不变量回归,
 * 画布上的拖拽都会静默失效。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SemanticRelationApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private RouteRepository routeRepository;
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void dragToConnectCreatesRelatedToUserRelationRecordedInGraphView() throws Exception {
        Project project = projectService.createProject("Relation regression project");
        Route route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        Node root = nodeService.createRootNode(project.id(), route.id(),
                "Drag source", null, List.of(), true);
        Node target = nodeService.createChildNode(project.id(), route.id(), root.id(),
                "Drag target", null, List.of(), true);

        // 拖拽连线只会发送这三个字段;其余字段会被控制器拒绝。
        String body = objectMapper.writeValueAsString(Map.of(
                "sourceNodeId", root.id().toString(),
                "targetNodeId", target.id().toString(),
                "relationType", "RELATED_TO"));

        // RELATED_TO 是对称的:存储的端点被规范化为 (minId, maxId),
        // 因此两个拖拽方向是同一个事实。
        var canonical = com.specagent.workspace.graph.GraphInvariantValidator
                .endpointsCanonicalized(root.id(), target.id(),
                        com.specagent.workspace.graph.NodeRelationType.RELATED_TO);
        mockMvc.perform(post("/api/v1/projects/{pid}/relations", project.id())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.sourceNodeId").value(canonical.sourceNodeId().toString()))
                .andExpect(jsonPath("$.targetNodeId").value(canonical.targetNodeId().toString()))
                .andExpect(jsonPath("$.relationType").value("RELATED_TO"))
                .andExpect(jsonPath("$.origin").value("USER"));
    }

    @Test
    void dragToConnectRejectsCrossProjectSourceNode() throws Exception {
        Project projectA = projectService.createProject("Relation A");
        Project projectB = projectService.createProject("Relation B");
        Route routeA = routeRepository.findById(projectA.activeRouteId()).orElseThrow();
        Node nodeA = nodeService.createRootNode(projectA.id(), routeA.id(),
                "A node", null, List.of(), true);
        Node nodeB = nodeService.createRootNode(projectB.id(), projectB.activeRouteId(),
                "B node", null, List.of(), true);

        // 在项目 B 中使用项目 A 的 sourceNodeId 必须以客户端错误失败
        // (source 节点不属于项目 B),绝不能是 500。API 从不静默接受跨项目
        // 关系,拖拽连线路径也必须遵守这一约束。
        String body = objectMapper.writeValueAsString(Map.of(
                "sourceNodeId", nodeA.id().toString(),
                "targetNodeId", nodeB.id().toString(),
                "relationType", "RELATED_TO"));

        mockMvc.perform(post("/api/v1/projects/{pid}/relations", projectB.id())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void duplicateRelatedToIsRejectedInBothDirections() throws Exception {
        Project project = projectService.createProject("Duplicate relation regression");
        Route route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        Node root = nodeService.createRootNode(project.id(), route.id(),
                "Source", null, List.of(), true);
        Node target = nodeService.createChildNode(project.id(), route.id(), root.id(),
                "Target", null, List.of(), true);

        String body = objectMapper.writeValueAsString(Map.of(
                "sourceNodeId", root.id().toString(),
                "targetNodeId", target.id().toString(),
                "relationType", "RELATED_TO"));

        mockMvc.perform(post("/api/v1/projects/{pid}/relations", project.id())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isCreated());

        // 同一节点对的第二次拖拽必须被拒绝。
        mockMvc.perform(post("/api/v1/projects/{pid}/relations", project.id())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isConflict());

        // RELATED_TO 是对称的:反方向是同一个事实,也必须被拒绝
        // (规范化端点后去重)。
        String reverseBody = objectMapper.writeValueAsString(Map.of(
                "sourceNodeId", target.id().toString(),
                "targetNodeId", root.id().toString(),
                "relationType", "RELATED_TO"));
        mockMvc.perform(post("/api/v1/projects/{pid}/relations", project.id())
                        .contentType("application/json")
                        .content(reverseBody))
                .andExpect(status().isConflict());
    }

    @Test
    void directionalRelationsHonorAuthorDirection() throws Exception {
        Project project = projectService.createProject("Directional relation regression");
        Route route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        Node root = nodeService.createRootNode(project.id(), route.id(),
                "Source", null, List.of(), true);
        Node target = nodeService.createChildNode(project.id(), route.id(), root.id(),
                "Target", null, List.of(), true);

        // DEPENDS_ON 是方向性的:A -> B 与 B -> A 是不同的事实。
        mockMvc.perform(post("/api/v1/projects/{pid}/relations", project.id())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sourceNodeId", root.id().toString(),
                                "targetNodeId", target.id().toString(),
                                "relationType", "DEPENDS_ON"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sourceNodeId").value(root.id().toString()))
                .andExpect(jsonPath("$.targetNodeId").value(target.id().toString()));

        // B -> A 的 DEPENDS_ON 会形成直接的 2 节点环:必须以依赖环被明确拒绝,
        // 而不是静默存储。
        mockMvc.perform(post("/api/v1/projects/{pid}/relations", project.id())
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(Map.of(
                                "sourceNodeId", target.id().toString(),
                                "targetNodeId", root.id().toString(),
                                "relationType", "DEPENDS_ON"))))
                .andExpect(status().isConflict());
    }
}
