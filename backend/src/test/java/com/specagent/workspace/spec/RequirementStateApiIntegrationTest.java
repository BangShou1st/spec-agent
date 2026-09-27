package com.specagent.workspace.spec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.AnswerCycleTestDriver;
import com.specagent.agent.DecisionCycleTestDriver;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerService;
import com.specagent.common.Ids;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.patch.AnswerPatchService;
import com.specagent.workspace.patch.Claim;
import com.specagent.workspace.patch.ClaimKind;
import com.specagent.workspace.patch.ClaimStatus;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectRepository;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.spec.RequirementClaimView;
import com.specagent.workspace.spec.RequirementStateView;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteLifecycleStatus;
import com.specagent.workspace.route.RouteService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:RequirementStateApiIntegrationTest.java
 *
 * 测试目标:需求状态读取接口(requirement-state)的集成测试。验证该接口
 * 是安全、只读、按路线范围的后端派生需求状态视图:绝不调用模型、绝不写状态、
 * 绝不泄漏兄弟路线的 Claim,在活跃指针不可信时快速失败。运行于默认假模型
 * 网关(不产生任何真实模型请求)。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RequirementStateApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ProjectRepository projectRepository;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AnswerPatchService answerPatchService;
    @Autowired
    private DecisionCycleTestDriver draftDriver;
    @Autowired
    private AnswerCycleTestDriver answerDriver;

    @Test
    void newProjectHasEmptyDerivedState() throws Exception {
        Project project = projectService.createProject("Empty state project");

        mockMvc.perform(get("/api/v1/projects/{projectId}/requirement-state", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(project.id().toString()))
                .andExpect(jsonPath("$.routeId").value(project.activeRouteId().toString()))
                .andExpect(jsonPath("$.confirmed").isEmpty())
                .andExpect(jsonPath("$.assumed").isEmpty())
                .andExpect(jsonPath("$.unresolved").isEmpty())
                .andExpect(jsonPath("$.rejected").isEmpty())
                .andExpect(jsonPath("$.builtAt").exists());
    }

    @Test
    void groupsClaimsByActualRuntimeStatusAfterAnswer() throws Exception {
        Project project = projectService.createProject("Grouped state project");
        // 走正常的运行时路径,使第一个 confirmed claim 派生自真实回答
        // (确定性引擎,无真实模型请求)。假引擎的 STATE_UPDATE 会提出一条
        // confirmed 的 goal claim。
        draftDriver.draftQuestion(project.id());
        answerDriver.submitFreeText(project.id(), "Primary outcome answer");

        // 通过运行时补丁服务在同一个活跃路线上添加 assumed、unresolved、
        // rejected 状态的 Claim,使每个状态分组都有数据。
        UUID activeRouteId = projectService.getProject(project.id()).orElseThrow().activeRouteId();
        UUID childTip = routeService.getRoute(activeRouteId).orElseThrow().tipNodeId();
        Answer extraAnswer = answerService.finalizeAnswer(project.id(), activeRouteId, childTip,
                null, "Assumption and rejection content", "test");
        answerPatchService.save(project.id(), activeRouteId, childTip, extraAnswer.id(),
                List.of(Claim.of(ClaimKind.ASSUMPTION, "Assumed scope detail", ClaimStatus.ASSUMED, null, null),
                        Claim.of(ClaimKind.OPEN_QUESTION, "Open follow-up detail", ClaimStatus.UNRESOLVED, null, null),
                        Claim.of(ClaimKind.OTHER, "Rejected idea detail", ClaimStatus.REJECTED, null, null)),
                null);

        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/requirement-state", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeId").value(activeRouteId.toString()))
                .andExpect(jsonPath("$.confirmed[0].kind").value("goal"))
                .andExpect(jsonPath("$.confirmed[0].status").value("confirmed"))
                .andExpect(jsonPath("$.confirmed[0].confidence").value(0.9))
                .andExpect(jsonPath("$.confirmed[0].sourceNodeId").exists())
                .andExpect(jsonPath("$.confirmed[0].sourceAnswerId").exists())
                .andExpect(jsonPath("$.unresolved[0].kind").value("open_question"))
                .andExpect(jsonPath("$.unresolved[0].status").value("unresolved"))
                .andExpect(jsonPath("$.assumed[0].text").value("Assumed scope detail"))
                .andExpect(jsonPath("$.assumed[0].status").value("assumed"))
                .andExpect(jsonPath("$.rejected[0].text").value("Rejected idea detail"))
                .andExpect(jsonPath("$.rejected[0].status").value("rejected"))
                .andReturn();

        RequirementStateView view = objectMapper.readValue(
                result.getResponse().getContentAsString(), RequirementStateView.class);
        assertThat(view.confirmed()).extracting(RequirementClaimView::text)
                .contains("The user clarified the main outcome.");
        assertThat(view.unresolved()).extracting(RequirementClaimView::text)
                .contains("Open follow-up detail");
        // 各分组互斥:每个 Claim 按其实际运行时状态恰好出现一次。
        assertThat(view.confirmed()).doesNotContain(view.unresolved().get(0));
    }

    @Test
    void activeRouteStateDoesNotExposeSiblingRouteClaims() throws Exception {
        Project project = projectService.createProject("Isolation project");
        // 活跃路线通过决策运行时获得真实的派生 Claim。
        draftDriver.draftQuestion(project.id());
        answerDriver.submitFreeText(project.id(), "Active route answer");
        UUID activeRouteId = projectService.getProject(project.id()).orElseThrow().activeRouteId();

        // 带有明确哨兵内容的兄弟路线,从未被激活。
        Route sibling = routeService.createRoute(project.id(), RouteLifecycleStatus.OPEN, "sibling route");
        Node siblingNode = nodeService.createRootNode(project.id(), sibling.id(),
                "Sibling question", null, List.of(), true);
        Answer siblingAnswer = answerService.finalizeAnswer(project.id(), sibling.id(),
                siblingNode.id(), null, "sibling sentinel answer", "test");
        answerPatchService.save(project.id(), sibling.id(), siblingNode.id(), siblingAnswer.id(),
                List.of(Claim.of(ClaimKind.GOAL, "SENTINEL_SIBLING_ONLY_CLAIM_9F3A",
                        ClaimStatus.CONFIRMED, siblingNode.id(), siblingAnswer.id())),
                null);

        // 活跃路线指针未变,仍指向原始路线。
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(activeRouteId);

        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/requirement-state", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeId").value(activeRouteId.toString()))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("The user clarified the main outcome.");
        assertThat(body).doesNotContain("SENTINEL_SIBLING_ONLY_CLAIM_9F3A");
        assertThat(body).doesNotContain("sibling sentinel answer");
    }

    @Test
    void unknownProjectReturnsNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/projects/{projectId}/requirement-state", Ids.random()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }

    @Test
    void noActiveRouteReturnsSafeEmptyReadModel() throws Exception {
        Project project = projectService.createProject("No active route project");
        projectRepository.updateActiveRoute(project.id(), null, Instant.now());

        mockMvc.perform(get("/api/v1/projects/{projectId}/requirement-state", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(project.id().toString()))
                .andExpect(jsonPath("$.routeId").isEmpty())
                .andExpect(jsonPath("$.confirmed").isEmpty())
                .andExpect(jsonPath("$.assumed").isEmpty())
                .andExpect(jsonPath("$.unresolved").isEmpty())
                .andExpect(jsonPath("$.rejected").isEmpty());
    }

    @Test
    void foreignActiveRouteFailsClosedWithoutExposingData() throws Exception {
        Project projectA = projectService.createProject("Owner A");
        Project projectB = projectService.createProject("Owner B");

        // 给项目 B 的路线加入明确的哨兵内容。
        UUID routeB = projectB.activeRouteId();
        Node nodeB = nodeService.createRootNode(projectB.id(), routeB,
                "B question", null, List.of(), true);
        Answer answerB = answerService.finalizeAnswer(projectB.id(), routeB,
                nodeB.id(), null, "FOREIGN_ROUTE_SENTINEL_77EE", "test");
        answerPatchService.save(projectB.id(), routeB, nodeB.id(), answerB.id(),
                List.of(Claim.of(ClaimKind.GOAL, "FOREIGN_ROUTE_SENTINEL_77EE",
                        ClaimStatus.CONFIRMED, nodeB.id(), answerB.id())),
                null);

        // 把 A 的活跃指针破坏为指向 B 的路线。
        projectRepository.updateActiveRoute(projectA.id(), routeB, Instant.now());

        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/requirement-state", projectA.id()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_INVARIANT_VIOLATION"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).doesNotContain("FOREIGN_ROUTE_SENTINEL_77EE");
        assertThat(body).doesNotContain("B question");
    }

    // ------------------------------------------------------------------
    // Phase 7.3A:按路线范围的需求状态读取。
    // ------------------------------------------------------------------

    private Route createRouteWithSentinelClaim(Project project, String sentinelText) {
        Route route = routeService.createRoute(project.id(), RouteLifecycleStatus.OPEN,
                "sentinel route");
        Node node = nodeService.createRootNode(project.id(), route.id(),
                "Sentinel question", null, List.of(), true);
        Answer answer = answerService.finalizeAnswer(project.id(), route.id(),
                node.id(), null, "sentinel answer " + sentinelText, "test");
        answerPatchService.save(project.id(), route.id(), node.id(), answer.id(),
                List.of(Claim.of(ClaimKind.GOAL, sentinelText,
                        ClaimStatus.CONFIRMED, node.id(), answer.id())),
                null);
        return route;
    }

    @Test
    void routeScopedReadReturnsExplicitRouteBWhileActiveRouteIsA() throws Exception {
        Project project = projectService.createProject("Route scoped project");
        // 活跃路线 A 通过决策运行时获得真实的派生 Claim。
        draftDriver.draftQuestion(project.id());
        answerDriver.submitFreeText(project.id(), "Active route answer");
        UUID activeRouteId = projectService.getProject(project.id()).orElseThrow().activeRouteId();
        Route routeB = createRouteWithSentinelClaim(project, "ROUTE_B_ONLY_CLAIM_5D1F");
        assertThat(activeRouteId).isNotEqualTo(routeB.id());

        MvcResult result = mockMvc.perform(get(
                "/api/v1/projects/{projectId}/routes/{routeId}/requirement-state",
                project.id(), routeB.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(project.id().toString()))
                .andExpect(jsonPath("$.routeId").value(routeB.id().toString()))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("ROUTE_B_ONLY_CLAIM_5D1F");
        // A 的活跃路线 Claim 绝不泄漏到显式的 B 路线读取中。
        assertThat(body).doesNotContain("The user clarified the main outcome.");
    }

    @Test
    void legacyActiveEndpointStillReturnsActiveRouteA() throws Exception {
        Project project = projectService.createProject("Legacy endpoint project");
        draftDriver.draftQuestion(project.id());
        answerDriver.submitFreeText(project.id(), "Active route answer");
        UUID activeRouteId = projectService.getProject(project.id()).orElseThrow().activeRouteId();
        createRouteWithSentinelClaim(project, "ROUTE_B_ONLY_CLAIM_9B17");

        MvcResult result = mockMvc.perform(get(
                "/api/v1/projects/{projectId}/requirement-state", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.routeId").value(activeRouteId.toString()))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body).contains("The user clarified the main outcome.");
        assertThat(body).doesNotContain("ROUTE_B_ONLY_CLAIM_9B17");
    }

    @Test
    void archivedRouteScopedReadRemainsAvailable() throws Exception {
        Project project = projectService.createProject("Archived scoped project");
        Route route = createRouteWithSentinelClaim(project, "ARCHIVED_ROUTE_CLAIM_3C21");
        routeService.archiveRoute(project.id(), route.id());

        mockMvc.perform(get(
                "/api/v1/projects/{projectId}/routes/{routeId}/requirement-state",
                project.id(), route.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(project.id().toString()))
                .andExpect(jsonPath("$.routeId").value(route.id().toString()))
                .andExpect(jsonPath("$.confirmed[0].text").value("ARCHIVED_ROUTE_CLAIM_3C21"));
    }

    @Test
    void supersededRouteScopedReadRemainsAvailable() throws Exception {
        Project project = projectService.createProject("Superseded scoped project");
        UUID activeRouteId = project.activeRouteId();
        Node root = nodeService.createRootNode(project.id(), activeRouteId, "Root question",
                null, List.of(), true);
        Node child = nodeService.createChildNode(project.id(), activeRouteId, root.id(),
                "Child question", null, List.of(), true);
        Answer answer = answerService.finalizeAnswer(project.id(), activeRouteId, root.id(),
                null, "old route answer", "test");
        answerPatchService.save(project.id(), activeRouteId, root.id(), answer.id(),
                List.of(Claim.of(ClaimKind.GOAL, "OLD_ROUTE_CLAIM_6E41",
                        ClaimStatus.CONFIRMED, root.id(), answer.id())),
                null);

        routeService.commitReplacementFromNode(project.id(), activeRouteId, child.id(), child.id(), null,
                "Replacement question", null, List.of(), true);
        assertThat(routeService.getRoute(activeRouteId).orElseThrow().lifecycleStatus())
                .isEqualTo(RouteLifecycleStatus.SUPERSEDED);

        mockMvc.perform(get(
                "/api/v1/projects/{projectId}/routes/{routeId}/requirement-state",
                project.id(), activeRouteId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(project.id().toString()))
                .andExpect(jsonPath("$.routeId").value(activeRouteId.toString()))
                .andExpect(jsonPath("$.confirmed[0].text").value("OLD_ROUTE_CLAIM_6E41"));
    }

    @Test
    void deletedRouteScopedReadRemainsAvailable() throws Exception {
        Project project = projectService.createProject("Deleted scoped project");
        Route route = createRouteWithSentinelClaim(project, "DELETED_ROUTE_CLAIM_8F22");
        routeService.softDeleteRoute(project.id(), route.id());

        mockMvc.perform(get(
                "/api/v1/projects/{projectId}/routes/{routeId}/requirement-state",
                project.id(), route.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectId").value(project.id().toString()))
                .andExpect(jsonPath("$.routeId").value(route.id().toString()))
                .andExpect(jsonPath("$.confirmed[0].text").value("DELETED_ROUTE_CLAIM_8F22"));
    }

    @Test
    void routeScopedMissingRouteReturnsNotFound() throws Exception {
        Project project = projectService.createProject("Missing route scoped project");

        mockMvc.perform(get(
                "/api/v1/projects/{projectId}/routes/{routeId}/requirement-state",
                project.id(), Ids.random()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
    }

    @Test
    void routeScopedForeignRouteReturnsNotFound() throws Exception {
        Project projectA = projectService.createProject("Owner A");
        Project projectB = projectService.createProject("Owner B");

        mockMvc.perform(get(
                "/api/v1/projects/{projectId}/routes/{routeId}/requirement-state",
                projectA.id(), projectB.activeRouteId()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"));
    }

    @Test
    void routeScopedUnknownProjectReturnsNotFound() throws Exception {
        mockMvc.perform(get(
                "/api/v1/projects/{projectId}/routes/{routeId}/requirement-state",
                Ids.random(), Ids.random()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_NOT_FOUND"));
    }
}
