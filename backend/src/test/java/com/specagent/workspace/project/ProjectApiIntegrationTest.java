package com.specagent.workspace.project;

import com.fasterxml.jackson.core.type.TypeReference;
import com.specagent.common.Ids;
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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:ProjectApiIntegrationTest.java
 *
 * 测试目标:项目 API 的集成测试,运行在常规测试环境与默认假模型网关之下
 * (不产生任何真实模型请求)。覆盖创建、重命名、标题唯一性(含大小写不敏感
 * 与删除后回收)、列表过滤与通配符转义、参数校验等契约。
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
    void createProjectWithDuplicateTitleRejected() throws Exception {
        projectService.createProject("Taken title");

        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Taken title\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_TITLE_ALREADY_EXISTS"));

        // 唯一性对大小写不敏感,与标题搜索语义一致。
        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"taken TITLE\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_TITLE_ALREADY_EXISTS"));
    }

    @Test
    void createProjectReusesTitleAfterTheOwnerIsDeleted() throws Exception {
        var project = projectService.createProject("Recycled title");

        mockMvc.perform(delete("/api/v1/projects/{id}", project.id()))
                .andExpect(status().isNoContent());

        // 删除项目会释放其标题:唯一性描述的是当前存在的项目,
        // 而不是历史上用过的所有标题。
        mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Recycled title\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("Recycled title"));
    }

    @Test
    void renameToAnExistingTitleRejected() throws Exception {
        var owner = projectService.createProject("Rename owner");
        projectService.createProject("Rename target");

        mockMvc.perform(put("/api/v1/projects/" + owner.id() + "/title")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Rename target\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROJECT_TITLE_ALREADY_EXISTS"));
    }

    @Test
    void renameKeepingOwnTitleAllowed() throws Exception {
        var owner = projectService.createProject("Stable title");

        // 项目改名为它当前已有的标题不算重复。
        mockMvc.perform(put("/api/v1/projects/" + owner.id() + "/title")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Stable title\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Stable title"));
    }

    @Test
    void renameProjectUpdatesTitleOnly() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/projects")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Old title\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String projectId = objectMapper.readValue(created.getResponse().getContentAsString(),
                com.fasterxml.jackson.databind.JsonNode.class).get("id").asText();

        mockMvc.perform(put("/api/v1/projects/" + projectId + "/title")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Renamed title\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(projectId))
                .andExpect(jsonPath("$.title").value("Renamed title"))
                .andExpect(jsonPath("$.activeRouteId").exists());

        // 列表读到新标题；路线等状态保持不变（activeRouteId 仍存在）。
        mockMvc.perform(get("/api/v1/projects?title=Renamed"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].title").value("Renamed title"));
    }

    @Test
    void renameUnknownProjectReturnsNotFound() throws Exception {
        mockMvc.perform(put("/api/v1/projects/" + UUID.randomUUID() + "/title")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"Anything\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    @Test
    void renameBlankTitleRejected() throws Exception {
        mockMvc.perform(put("/api/v1/projects/" + UUID.randomUUID() + "/title")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\": \"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
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
        Thread.sleep(5); // created_at 不同,保证排序确定
        var p2 = projectService.createProject("Second");

        MvcResult result = mockMvc.perform(get("/api/v1/projects"))
                .andExpect(status().isOk())
                .andReturn();

        List<ProjectSummaryResponse> projects = objectMapper.readValue(
                result.getResponse().getContentAsString(),
                new TypeReference<List<ProjectSummaryResponse>>() {
                });

        // 共享测试库中可能存在其他非事务测试创建的项目;这里只断言本测试
        // 创建的项目。
        List<UUID> ids = projects.stream().map(ProjectSummaryResponse::id).toList();
        assertThat(ids).contains(p1.id(), p2.id());
        // 排序确定:created_at ASC。
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

    @Test
    void listProjectsFiltersByTitleCaseInsensitively() throws Exception {
        var alpha = projectService.createProject("Alpha requirements");
        Thread.sleep(5); // created_at 不同,保证排序确定
        var beta = projectService.createProject("Beta login flow");
        Thread.sleep(5);
        var lower = projectService.createProject("alpha lowercase");

        MvcResult result = mockMvc.perform(get("/api/v1/projects").param("title", "alpha"))
                .andExpect(status().isOk())
                .andReturn();
        List<UUID> ids = parse(result).stream().map(ProjectSummaryResponse::id).toList();

        // 大小写不敏感的子串匹配:"Alpha" 与 "alpha" 都命中。
        assertThat(ids).contains(alpha.id(), lower.id());
        assertThat(ids).doesNotContain(beta.id());
        // 排序保持确定:created_at ASC。
        assertThat(ids).containsSubsequence(alpha.id(), lower.id());
    }

    @Test
    void listProjectsWithBlankTitleReturnsAll() throws Exception {
        var p1 = projectService.createProject("First");
        Thread.sleep(5);
        var p2 = projectService.createProject("Second");

        MvcResult empty = mockMvc.perform(get("/api/v1/projects").param("title", ""))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult blank = mockMvc.perform(get("/api/v1/projects").param("title", "   "))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(parse(empty).stream().map(ProjectSummaryResponse::id).toList()).contains(p1.id(), p2.id());
        assertThat(parse(blank).stream().map(ProjectSummaryResponse::id).toList()).contains(p1.id(), p2.id());
    }

    @Test
    void listProjectsEscapesLikeWildcards() throws Exception {
        var withPercent = projectService.createProject("100% complete");
        Thread.sleep(5);
        var plain = projectService.createProject("100 percent done");

        // 查询中的字面 '%' 必须只匹配确实包含 '%' 的标题,
        // 证明 ILIKE 通配符被转义而非被解释。
        MvcResult result = mockMvc.perform(get("/api/v1/projects").param("title", "100%"))
                .andExpect(status().isOk())
                .andReturn();
        List<UUID> ids = parse(result).stream().map(ProjectSummaryResponse::id).toList();

        assertThat(ids).contains(withPercent.id());
        assertThat(ids).doesNotContain(plain.id());
    }

    private List<ProjectSummaryResponse> parse(MvcResult result) throws java.io.IOException {
        return objectMapper.readValue(
                result.getResponse().getContentAsString(),
                new TypeReference<List<ProjectSummaryResponse>>() {
                });
    }
}