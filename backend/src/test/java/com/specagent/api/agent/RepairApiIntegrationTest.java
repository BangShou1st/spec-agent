package com.specagent.api.agent;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.AgentTaskType;
import com.specagent.agent.AgentAction;
import com.specagent.testing.FakeModelAdapter;
import com.specagent.agent.ModelRequest;
import com.specagent.agent.ModelResponse;
import com.specagent.agent.contracts.AnswerInterpretationResult;
import com.specagent.agent.contracts.NodeDraft;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerRepository;
import com.specagent.answer.AnswerService;
import com.specagent.common.Json;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteService;
import com.specagent.model.gateway.ModelGatewayErrorCategory;
import com.specagent.model.gateway.ModelGatewayException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Repair command integration tests: an already persisted immutable answer
 * whose post-answer processing failed is resumed without finalizing a second
 * answer and without overwriting the immutable answer.
 *
 * <p>Deliberately not {@code @Transactional}: the failure phase throws inside
 * the request, and the FAILED run is recorded through a {@code REQUIRES_NEW}
 * transaction that must observe the already-persisted run row.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RepairApiIntegrationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private RouteService routeService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private AnswerService answerService;
    @Autowired
    private AnswerRepository answerRepository;
    @Autowired
    private AgentRunService agentRunService;
    @Autowired
    private Json json;

    @MockBean
    private FakeModelAdapter fakeModelAdapter;

    private final AtomicBoolean invalidPatch = new AtomicBoolean(true);

    private void stubHappyModel() {
        when(fakeModelAdapter.run(any(ModelRequest.class))).thenAnswer(invocation -> {
            ModelRequest request = invocation.getArgument(0);
            Map<String, String> trace = Map.of("adapter", "mock");
            return switch (request.taskType()) {
                case DRAFT_NODE -> new ModelResponse(
                        request.agentRunId(), request.contextSnapshotId(), request.taskType(),
                        AgentAction.ASK_NEXT_QUESTION,
                        json.write(new NodeDraft("What is the most important outcome?",
                                "Clarifies the primary requirement goal.", List.of(), true)),
                        trace);
                case INTERPRET_ANSWER -> new ModelResponse(
                        request.agentRunId(), request.contextSnapshotId(), request.taskType(),
                        AgentAction.INTERPRET_ANSWER,
                        json.write(new AnswerInterpretationResult(
                                List.of("The user clarified the main outcome."),
                                List.of(), List.of(), List.of())),
                        trace);
                case DRAFT_ANSWER_PATCH -> new ModelResponse(
                        request.agentRunId(), request.contextSnapshotId(), request.taskType(),
                        AgentAction.INTERPRET_ANSWER,
                        json.write(invalidPatch.get()
                                ? Map.of("claims", List.of(
                                        Map.of("kind", "goal", "text", " ",
                                                "status", "confirmed", "confidence", 0.9)))
                                : Map.of("claims", List.of(
                                        Map.of("kind", "goal", "text", "The user clarified the main outcome.",
                                                "status", "confirmed", "confidence", 0.9),
                                        Map.of("kind", "open_question", "text", "Scope must be confirmed.",
                                                "status", "unresolved", "confidence", 0.6)))),
                        trace);
                default -> throw new IllegalStateException("Unexpected task " + request.taskType());
            };
        });
    }

    @Test
    void repairResumesFailedAnswerWithoutCreatingSecondAnswer() throws Exception {
        stubHappyModel();
        Project project = projectService.createProject("API repair project");

        mockMvc.perform(post("/api/v1/projects/{projectId}/questions/next", project.id()))
                .andExpect(status().isOk());
        UUID rootNodeId = routeService.getRoute(project.activeRouteId()).orElseThrow().tipNodeId();

        // Phase 1: invalid patch draft -> 422, run FAILED, answer persisted.
        mockMvc.perform(post("/api/v1/projects/{projectId}/answers", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"freeText\": \"clarified scope\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("MODEL_CONTRACT_REJECTED"))
                .andExpect(jsonPath("$.message").value("The model output did not satisfy the runtime contract"));

        AgentRun failedRun = agentRunService.listByProject(project.id()).get(1);
        assertThat(failedRun.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(failedRun.producedAnswerId()).isNotNull();
        assertThat(failedRun.producedPatchId()).isNull();
        Answer existingAnswer = answerService.getAnswer(failedRun.producedAnswerId()).orElseThrow();
        assertThat(routeService.getRoute(project.activeRouteId()).orElseThrow().tipNodeId())
                .isEqualTo(rootNodeId);

        // Phase 2: duplicate submission while the tip is still answered fails
        // closed with 409.
        mockMvc.perform(post("/api/v1/projects/{projectId}/answers", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"freeText\": \"duplicate attempt\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_ALREADY_FINALIZED"));

        // Phase 3: repair the existing immutable answer.
        invalidPatch.set(false);
        mockMvc.perform(post("/api/v1/projects/{projectId}/answers/{answerId}/repair",
                        project.id(), existingAnswer.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentRun.status").value("completed"))
                .andExpect(jsonPath("$.agentRun.producedAnswerId").value(existingAnswer.id().toString()))
                .andExpect(jsonPath("$.answer.id").value(existingAnswer.id().toString()))
                .andExpect(jsonPath("$.answer.freeText").value("clarified scope"))
                .andExpect(jsonPath("$.answerPatch.claims[0].kind").exists())
                .andExpect(jsonPath("$.nextNode.parentNodeId").value(rootNodeId.toString()));

        // No second answer was created for the same route/node.
        assertThat(answerRepository.findByRouteAndNodeIds(project.activeRouteId(), List.of(rootNodeId)))
                .hasSize(1);
    }

    @Test
    void repairWrongProjectAnswerRejected() throws Exception {
        Project projectA = projectService.createProject("Repair owner A");
        Node node = nodeService.createRootNode(projectA.id(), projectA.activeRouteId(),
                "Question", null, List.of(), true);
        Answer answer = answerService.finalizeAnswer(
                projectA.id(), projectA.activeRouteId(), node.id(), null, "clarified", "user");
        Project projectB = projectService.createProject("Repair owner B");

        mockMvc.perform(post("/api/v1/projects/{projectId}/answers/{answerId}/repair",
                        projectB.id(), answer.id()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANSWER_NOT_FOUND"));
    }

    @Test
    void repairAnswerNotInActiveFlowRejected() throws Exception {
        Project project = projectService.createProject("Repair inactive flow");
        Node root = nodeService.createRootNode(project.id(), project.activeRouteId(),
                "Root question", null, List.of(), true);
        Answer answer = answerService.finalizeAnswer(
                project.id(), project.activeRouteId(), root.id(), null, "old answer", "user");

        // Fork away: the answer's route is no longer the active flow.
        Route fork = routeService.forkFromNode(project.id(), project.activeRouteId(), root.id(), "fork");
        assertThat(projectService.getProject(project.id()).orElseThrow().activeRouteId())
                .isEqualTo(fork.id());

        mockMvc.perform(post("/api/v1/projects/{projectId}/answers/{answerId}/repair",
                        project.id(), answer.id()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ANSWER_NOT_IN_ACTIVE_FLOW"));
    }

    @Test
    void repairUnknownAnswerRejected() throws Exception {
        Project project = projectService.createProject("Repair unknown answer");

        mockMvc.perform(post("/api/v1/projects/{projectId}/answers/{answerId}/repair",
                        project.id(), UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ANSWER_NOT_FOUND"));
    }

    @Test
    void repairApiReusesCheckpointWhenNextNodeGenerationFails() throws Exception {
        AtomicBoolean failNextNode = new AtomicBoolean(false);
        when(fakeModelAdapter.run(any(ModelRequest.class))).thenAnswer(invocation -> {
            ModelRequest request = invocation.getArgument(0);
            if (request.taskType() == AgentTaskType.DRAFT_NODE && failNextNode.get()) {
                throw new ModelGatewayException(ModelGatewayErrorCategory.CONNECTION,
                        "deterministic next node failure");
            }
            Map<String, String> trace = Map.of("adapter", "mock");
            return switch (request.taskType()) {
                case DRAFT_NODE -> new ModelResponse(request.agentRunId(), request.contextSnapshotId(),
                        request.taskType(), AgentAction.ASK_NEXT_QUESTION,
                        json.write(new NodeDraft("What should be clarified next?", "Purpose",
                                List.of(), true)), trace);
                case INTERPRET_ANSWER -> new ModelResponse(request.agentRunId(), request.contextSnapshotId(),
                        request.taskType(), AgentAction.INTERPRET_ANSWER,
                        json.write(new AnswerInterpretationResult(List.of("A goal was stated."),
                                List.of(), List.of(), List.of())), trace);
                case DRAFT_ANSWER_PATCH -> new ModelResponse(request.agentRunId(), request.contextSnapshotId(),
                        request.taskType(), AgentAction.INTERPRET_ANSWER,
                        json.write(Map.of("claims", List.of(Map.of("kind", "goal",
                                "text", "A goal was stated.", "status", "confirmed", "confidence", 0.9)))), trace);
                default -> throw new IllegalStateException("Unexpected task " + request.taskType());
            };
        });

        Project project = projectService.createProject("API checkpoint reuse");
        mockMvc.perform(post("/api/v1/projects/{projectId}/questions/next", project.id()))
                .andExpect(status().isOk());
        UUID nodeId = routeService.getRoute(project.activeRouteId()).orElseThrow().tipNodeId();
        clearInvocations(fakeModelAdapter);
        failNextNode.set(true);

        mockMvc.perform(post("/api/v1/projects/{projectId}/answers", project.id())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"freeText\": \"goal\"}"))
                .andExpect(status().isBadGateway());

        UUID answerId = answerRepository.findByRouteAndNodeIds(project.activeRouteId(), List.of(nodeId))
                .get(0).id();
        UUID patchId = agentRunService.listByProject(project.id()).get(1).producedPatchId();
        clearInvocations(fakeModelAdapter);
        failNextNode.set(false);

        mockMvc.perform(post("/api/v1/projects/{projectId}/answers/{answerId}/repair",
                        project.id(), answerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answerPatch.id").value(patchId.toString()));

        verify(fakeModelAdapter, never()).run(org.mockito.ArgumentMatchers.argThat(
                request -> request.taskType() == AgentTaskType.INTERPRET_ANSWER));
        verify(fakeModelAdapter, never()).run(org.mockito.ArgumentMatchers.argThat(
                request -> request.taskType() == AgentTaskType.DRAFT_ANSWER_PATCH));
        verify(fakeModelAdapter, times(1)).run(org.mockito.ArgumentMatchers.argThat(
                request -> request.taskType() == AgentTaskType.DRAFT_NODE));
        assertThat(answerRepository.findByRouteAndNodeIds(project.activeRouteId(), List.of(nodeId)))
                .hasSize(1);
    }
}
