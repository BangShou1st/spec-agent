package com.specagent.api.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.api.node.NodeResponse;
import com.specagent.api.route.RouteMutationResponse;
import com.specagent.agent.FakeModelAdapter;
import com.specagent.agent.ModelRequest;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Answer isolation at the model-facing envelope level, exercised through the
 * command API. The spy delegates to the real deterministic fake and captures
 * every {@code ModelRequest} the orchestrator sends. After forking to a new
 * active route, every subsequent answer request must contain the active
 * lineage (shared root, run-local answer) and exclude the sibling sentinel,
 * sibling answers/patches/nodes, and the old route id.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AnswerRouteIsolationApiIntegrationTest {

    private static final String SIBLING_SENTINEL = "API_SIBLING_SENTINEL_DO_NOT_LEAK_9d4b";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ObjectMapper objectMapper;

    @SpyBean
    private FakeModelAdapter fakeModelAdapter;

    private final List<ModelRequest> captured = new ArrayList<>();

    @BeforeEach
    void captureModelRequests() {
        captured.clear();
        doAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return invocation.callRealMethod();
        }).when(fakeModelAdapter).run(any(ModelRequest.class));
    }

    @Test
    void forkAnswerRequestsExcludeSiblingContent() throws Exception {
        Project project = projectService.createProject("API answer isolation");

        NodeResponse root = draftNext(project.id());

        // Answer the root and then a second node on route R1 with a unique
        // sibling sentinel.
        AnswerExecutionResponse rootAnswer = submitAnswer(project.id(), "Root answer stays on R1");
        NodeResponse nodeA = rootAnswer.nextNode();
        AnswerExecutionResponse branchAnswer = submitAnswer(
                project.id(), SIBLING_SENTINEL + " the sibling branch answer");
        NodeResponse nodeA2 = branchAnswer.nextNode();
        UUID r1RouteId = branchAnswer.agentRun().routeId();
        UUID r1AnswerId = branchAnswer.answer().id();
        UUID r1PatchId = branchAnswer.answerPatch().id();

        // Fork from the shared root: R2 becomes active.
        RouteMutationResponse fork = fork(project.id(), root.id());
        UUID r2RouteId = fork.route().id();

        int forkPoint = captured.size();
        AnswerExecutionResponse forkAnswer = submitAnswer(
                project.id(), "Fork branch answer local to R2");

        assertThat(forkAnswer.nextNode().parentNodeId()).isEqualTo(root.id());
        List<ModelRequest> forkRequests = captured.subList(forkPoint, captured.size());
        assertThat(forkRequests).isNotEmpty();

        for (ModelRequest request : forkRequests) {
            assertThat(request.inputJson())
                    .as("fork request for task %s must exclude sibling content", request.taskType())
                    .doesNotContain(SIBLING_SENTINEL)
                    .doesNotContain("node:" + nodeA.id())
                    .doesNotContain("node:" + nodeA2.id())
                    .doesNotContain("answer:" + r1AnswerId)
                    .doesNotContain("patch:" + r1PatchId)
                    .doesNotContain("route:" + r1RouteId)
                    // Active lineage and run-local answer stay present.
                    .contains("node:" + root.id())
                    .contains("route:" + r2RouteId)
                    .contains("Fork branch answer local to R2");
        }

        // The R1 route id never appears in any fork request.
        assertThat(captured.subList(forkPoint, captured.size()))
                .allSatisfy(request -> assertThat(request.inputJson()).doesNotContain("route:" + r1RouteId));
    }

    private NodeResponse draftNext(UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects/{projectId}/questions/next", projectId))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), DraftQuestionResponse.class)
                .producedNode();
    }

    private AnswerExecutionResponse submitAnswer(UUID projectId, String freeText) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects/{projectId}/answers", projectId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"freeText\": \"" + freeText + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), AnswerExecutionResponse.class);
    }

    private RouteMutationResponse fork(UUID projectId, UUID nodeId) throws Exception {
        UUID sourceRouteId = projectService.getProject(projectId).orElseThrow().activeRouteId();
        MvcResult result = mockMvc.perform(post("/api/v1/projects/{projectId}/nodes/{nodeId}/fork",
                        projectId, nodeId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceRouteId\": \"" + sourceRouteId
                                + "\", \"label\": \"isolation fork\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), RouteMutationResponse.class);
    }
}
