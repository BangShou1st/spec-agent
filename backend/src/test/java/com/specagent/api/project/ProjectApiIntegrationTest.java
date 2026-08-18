package com.specagent.api.project;

import com.fasterxml.jackson.core.type.TypeReference;
import com.specagent.common.Ids;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Project API integration tests. Runs against the normal test runtime setup
 * with the default fake model gateway; zero public provider requests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ProjectApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Test
    void createProjectSuccess() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"New product requirements\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.title").value("New product requirements"))
                .andExpect(jsonPath("$.activeRouteId").exists())
                .andExpect(jsonPath("$.defaultProfileId").exists())
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.updatedAt").exists());
    }

    @Test
    void blankTitleRejected() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("Request validation failed"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("title"));
    }

    @Test
    void missingTitleRejected() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void overlyLongTitleRejected() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"" + "a".repeat(300) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void malformedRequestBodyRejected() throws Exception {
        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_JSON"));
    }

    @Test
    void getProjectSuccess() throws Exception {
        var project = projectService.createProject("A visible project");

        mockMvc.perform(get("/api/v1/projects/{id}", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(project.id().toString()))
                .andExpect(jsonPath("$.title").value("A visible project"))
                .andExpect(jsonPath("$.activeRouteId").value(project.activeRouteId().toString()));
    }

    @Test
    void unknownProjectReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{id}", Ids.random()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    @Test
    void listProjects() throws Exception {
        var p1 = projectService.createProject("First");
        Thread.sleep(5); // distinct created_at so ordering is deterministic
        var p2 = projectService.createProject("Second");

        MvcResult result = mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isOk())
                .andReturn();

        List<ProjectSummaryResponse> projects = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                new TypeReference<List<ProjectSummaryResponse>>() {
                });

        // The shared test database may contain projects created by other
        // non-transactional tests; only the projects created here are asserted.
        List<UUID> ids = projects.stream().map(ProjectSummaryResponse::id).toList();
        assertThat(ids).contains(p1.id(), p2.id());
        // Deterministic order: created_at ASC.
        assertThat(projects.stream().map(ProjectSummaryResponse::id).toList())
                .containsSubsequence(p1.id(), p2.id());
        assertThat(projects).extracting(ProjectSummaryResponse::title).contains("First", "Second");
    }

    @Test
    void malformedProjectIdReturnsBadRequest() throws Exception {
        mockMvc.perform(get("/api/v1/projects/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_UUID"));
    }
}