package com.specagent.workspace.graph;

import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:GraphOperationApiIntegrationTest.java
 *
 * 测试目标:graph-operations 相关接口必须返回 200 且响应体可被 JSON 序列化。
 *
 * 回归背景:领域对象 {@code GraphOperation} 使用 record 风格的访问器
 * ({@code id()}、{@code type()} 等),Jackson 默认的 bean 探测无法识别,
 * 直接序列化领域对象会报 "no properties discovered"。undo/redo 先提交事务、
 * 响应才 500——客户端看到错误,但状态其实已经改变。这些用例通过
 * {@code GraphOperationResponse} DTO 锁定响应契约。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class GraphOperationApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;

    @Test
    void undoAndRedoAnswer200WithJsonSafeOperationBody() throws Exception {
        Project project = projectService.createProject("操作日志接口测试");
        UUID projectId = project.id();
        UUID routeId = project.activeRouteId();

        mockMvc.perform(post("/api/v1/projects/{projectId}/nodes", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routeId\":\"" + routeId
                                + "\",\"subtype\":\"NOTE\",\"content\":{\"text\":\"待撤销的草稿节点\"}}"))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/v1/projects/{projectId}/graph-operations", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").isNotEmpty())
                .andExpect(jsonPath("$[0].type").value("CREATE_DRAFT_NODE"))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].afterRefs.nodeId").isNotEmpty());

        MvcResult undo = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/graph-operations/undo", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation.type").value("CREATE_DRAFT_NODE"))
                .andExpect(jsonPath("$.operation.status").value("UNDONE"))
                .andExpect(jsonPath("$.description").isNotEmpty())
                // 反馈必须说明撤销的是"什么",而不只是操作类型:undo 可能
                // 补偿掉 agent 刚生成的节点,用户无法从"创建草稿节点"里
                // 认出具体是哪个节点。
                .andExpect(jsonPath("$.targetTitle").value("待撤销的草稿节点"))
                .andReturn();

        mockMvc.perform(get("/api/v1/projects/{projectId}/graph-operations/availability", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canUndo").value(false))
                .andExpect(jsonPath("$.canRedo").value(true));

        mockMvc.perform(post("/api/v1/projects/{projectId}/graph-operations/redo", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation.status").value("ACTIVE"))
                .andExpect(jsonPath("$.description").isNotEmpty())
                .andExpect(jsonPath("$.targetTitle").value("待撤销的草稿节点"));
    }
}
