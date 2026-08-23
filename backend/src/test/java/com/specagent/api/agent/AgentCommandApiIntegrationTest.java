package com.specagent.api.agent;

import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
 * Draft-next-question legacy command integration tests (default fake gateway,
 * zero public provider calls).
 *
 * <p>Answer/repair command tests moved to {@link AnswerCycleRunApiIntegrationTest}
 * when the answer flow cut over to the async AgentRun surface; the input-policy
 * scenarios (option ownership, free-text permission) are enforced by
 * {@code AnswerCycleService} and covered there at execution time.
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
}
