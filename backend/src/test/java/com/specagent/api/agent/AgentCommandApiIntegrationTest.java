package com.specagent.api.agent;

import com.specagent.node.Node;
import com.specagent.node.NodeOption;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.RouteLifecycleStatus;
import com.specagent.route.RouteService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Draft-next-question and answer command integration tests (default fake
 * gateway, zero public provider calls).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AgentCommandApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private RouteService routeService;

    private static final String FAKE_QUESTION = "What is the most important outcome?";

    @Test
    void draftNextQuestionCreatesRootNodeAndRun() throws Exception {
        Project project = projectService.createProject("Draft project");

        mockMvc.perform(post("/api/v1/projects/{projectId}/questions/next", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentRun.id").exists())
                .andExpect(jsonPath("$.agentRun.status").value("completed"))
                .andExpect(jsonPath("$.agentRun.producedNodeId").exists())
                .andExpect(jsonPath("$.producedNode.id").exists())
                .andExpect(jsonPath("$.producedNode.projectId").value(project.id().toString()))
                .andExpect(jsonPath("$.producedNode.parentNodeId").isEmpty())
                .andExpect(jsonPath("$.producedNode.question").value(FAKE_QUESTION));
    }

    @Test
    void draftNextQuestionResponseExposesNoRawModelMaterial() throws Exception {
        Project project = projectService.createProject("Draft safety");

        MvcResult result = mockMvc.perform(post("/api/v1/projects/{projectId}/questions/next", project.id()))
                .andExpect(status().isOk())
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .doesNotContain("inputJson")
                .doesNotContain("outputJson")
                .doesNotContain("\"context\":{")
                .doesNotContain("provider")
                .doesNotContain("Bearer");
    }

    @Test
    void draftNextQuestionCreatesChildWhenTipExists() throws Exception {
        Project project = projectService.createProject("Draft child project");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Root question", null, List.of(), true);

        mockMvc.perform(post("/api/v1/projects/{projectId}/questions/next", project.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.producedNode.parentNodeId").value(root.id().toString()));
    }

    @Test
    void freeTextAnswerSucceedsThroughOrchestrator() throws Exception {
        Project project = projectService.createProject("Answer project");
        // Draft the root through the API first so the answer loop is fully real.
        mockMvc.perform(post("/api/v1/projects/{projectId}/questions/next", project.id()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/projects/{projectId}/answers", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"freeText\": \"We need a single-user tool\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentRun.status").value("completed"))
                .andExpect(jsonPath("$.agentRun.producedAnswerId").exists())
                .andExpect(jsonPath("$.agentRun.producedPatchId").exists())
                .andExpect(jsonPath("$.agentRun.producedNodeId").exists())
                .andExpect(jsonPath("$.answer.freeText").value("We need a single-user tool"))
                .andExpect(jsonPath("$.answer.selectedOptionId").isEmpty())
                .andExpect(jsonPath("$.answerPatch.claims[0].kind").exists())
                .andExpect(jsonPath("$.nextNode.parentNodeId").exists());
    }

    @Test
    void selectedOptionAnswerSucceedsAndPersistsOption() throws Exception {
        Project project = projectService.createProject("Option answer project");
        NodeOption optionA = NodeOption.of("Small scope", "Affects scope");
        NodeOption optionB = NodeOption.of("Large scope", "Affects scope");
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Pick a scope", "Prompts a choice", List.of(optionA, optionB), false);

        mockMvc.perform(post("/api/v1/projects/{projectId}/answers", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedOptionId\": \"" + optionA.id() + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentRun.status").value("completed"))
                .andExpect(jsonPath("$.answer.selectedOptionId").value(optionA.id().toString()))
                .andExpect(jsonPath("$.answer.freeText").isEmpty())
                .andExpect(jsonPath("$.nextNode").exists());
    }

    @Test
    void freeTextRejectedWhenNodeDoesNotAllowIt() throws Exception {
        Project project = projectService.createProject("Options only project");
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Pick one", null, List.of(NodeOption.of("A", "a")), false);

        mockMvc.perform(post("/api/v1/projects/{projectId}/answers", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"freeText\": \"free text not allowed here\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void emptyAnswerRejected() throws Exception {
        Project project = projectService.createProject("Empty answer project");
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Question", null, List.of(), true);

        mockMvc.perform(post("/api/v1/projects/{projectId}/answers", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void selectedOptionFromAnotherNodeRejected() throws Exception {
        Project project = projectService.createProject("Cross node option");
        NodeOption rootOption = NodeOption.of("Root option", "root");
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Root scope", null, List.of(rootOption), true);
        // The tip is now a child node with its own options.
        NodeOption tipOption = NodeOption.of("Tip option", "tip");
        nodeService.createChildNode(project.id(), project.activeRouteId(),
                projectActiveTip(project), "Tip scope", null, List.of(tipOption), false);

        mockMvc.perform(post("/api/v1/projects/{projectId}/answers", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedOptionId\": \"" + rootOption.id() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void randomOptionIdRejected() throws Exception {
        Project project = projectService.createProject("Random option");
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Question", null, List.of(NodeOption.of("A", "a")), false);

        mockMvc.perform(post("/api/v1/projects/{projectId}/answers", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedOptionId\": \"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void selectedOptionFromSiblingRouteRejected() throws Exception {
        Project project = projectService.createProject("Sibling option");
        NodeOption activeOption = NodeOption.of("Active option", "active");
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Active node", null, List.of(activeOption), false);

        // Sibling route with its own node and options.
        var sibling = routeService.createRoute(project.id(), RouteLifecycleStatus.OPEN, "sibling route");
        NodeOption siblingOption = NodeOption.of("Sibling option", "sibling");
        nodeService.createRootNode(project.id(), sibling.id(),
                "Sibling node", null, List.of(siblingOption), false);

        mockMvc.perform(post("/api/v1/projects/{projectId}/answers", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedOptionId\": \"" + siblingOption.id() + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void optionAndFreeTextBothPreservedWhenAllowed() throws Exception {
        Project project = projectService.createProject("Both inputs project");
        NodeOption option = NodeOption.of("Option choice", "impact");
        nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Question", null, List.of(option), true);

        mockMvc.perform(post("/api/v1/projects/{projectId}/answers", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"selectedOptionId\": \"" + option.id()
                                + "\", \"freeText\": \"explanation text\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer.selectedOptionId").value(option.id().toString()))
                .andExpect(jsonPath("$.answer.freeText").value("explanation text"));
    }

    private UUID projectActiveTip(Project project) {
        return routeService.getRoute(project.activeRouteId())
                .orElseThrow().tipNodeId();
    }
}