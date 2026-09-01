package com.specagent.api.graph;

import com.specagent.project.Project;
import com.specagent.project.ProjectService;
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
 * The graph-operations endpoints must answer 200 with JSON-safe bodies.
 *
 * <p>Regression: the domain {@code GraphOperation} exposes record-style
 * accessors ({@code id()}, {@code type()}, ...) that Jackson's default bean
 * detection cannot see, so serializing the raw domain object failed with
 * "no properties discovered". Undo/redo committed their transaction and only
 * then the response 500-ed — the client saw an error while the state had
 * already changed. These tests pin the response contract through the
 * {@code GraphOperationResponse} DTO.
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
                        .content("{\"routeId\":\"" + routeId + "\",\"subtype\":\"NOTE\",\"content\":{}}"))
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
                .andReturn();

        mockMvc.perform(get("/api/v1/projects/{projectId}/graph-operations/availability", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canUndo").value(false))
                .andExpect(jsonPath("$.canRedo").value(true));

        mockMvc.perform(post("/api/v1/projects/{projectId}/graph-operations/redo", projectId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operation.status").value("ACTIVE"))
                .andExpect(jsonPath("$.description").isNotEmpty());
    }
}
