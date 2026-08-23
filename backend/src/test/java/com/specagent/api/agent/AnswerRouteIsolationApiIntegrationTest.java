package com.specagent.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.api.node.NodeResponse;
import com.specagent.api.route.RouteMutationResponse;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Answer isolation at the model-facing envelope level, exercised through the
 * async AgentRun command surface. The spy delegates to the real deterministic
 * engine and captures every {@link AgentRequestEnvelope} the runtime sends.
 * After forking to a new active route, every subsequent answer run must carry
 * only the active lineage (shared root, run-local answer) and exclude the
 * sibling sentinel, sibling answers/patches/nodes, and the old route id.
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
    @Autowired
    private RunService runService;
    @Autowired
    private RunWorker worker;
    @Autowired
    private com.specagent.node.NodeService nodeService;

    @SpyBean
    private AgentDecisionEngine decisionEngine;

    private final List<AgentRequestEnvelope> captured = new ArrayList<>();

    @BeforeEach
    void captureModelRequests() {
        captured.clear();
        doAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return invocation.callRealMethod();
        }).when(decisionEngine).runStateUpdate(any(AgentRequestEnvelope.class));
        doAnswer(invocation -> {
            captured.add(invocation.getArgument(0));
            return invocation.callRealMethod();
        }).when(decisionEngine).runDecision(any(AgentRequestEnvelope.class));
    }

    /** Flattened envelope projection used for exclusion assertions. */
    private String envelopeText(AgentRequestEnvelope envelope) {
        StringBuilder combined = new StringBuilder();
        combined.append("route:").append(envelope.snapshot().routeId()).append('\n');
        // The run-local event carries the triggering answer input.
        if (envelope.event() != null && envelope.event().freeText() != null) {
            combined.append(envelope.event().freeText()).append('\n');
        }
        for (var entry : envelope.snapshot().lineage()) {
            if (entry.node() != null) {
                combined.append("node:").append(entry.node().id()).append('\n');
                var body = entry.node().body();
                if (body != null && body.text() != null) {
                    combined.append(body.text()).append('\n');
                }
            }
            if (entry.answer() != null) {
                combined.append("answer:").append(entry.answer().id()).append('\n');
                if (entry.answer().freeText() != null) {
                    combined.append(entry.answer().freeText()).append('\n');
                }
            }
            if (entry.patches() != null) {
                for (var patch : entry.patches()) {
                    combined.append("patch:").append(patch.id()).append('\n');
                }
            }
        }
        return combined.toString();
    }

    @Test
    void forkAnswerRequestsExcludeSiblingContent() throws Exception {
        Project project = projectService.createProject("API answer isolation");

        NodeResponse root = draftNext(project.id());

        // Answer the root and then a second node on route R1 with a unique
        // sibling sentinel.
        AnswerRunView rootAnswer = submitAnswer(project.id(), "Root answer stays on R1");
        NodeResponse nodeA = nextNodeAfter(rootAnswer);
        AnswerRunView branchAnswer = submitAnswer(
                project.id(), SIBLING_SENTINEL + " the sibling branch answer");
        NodeResponse nodeA2 = nextNodeAfter(branchAnswer);
        UUID r1RouteId = branchAnswer.routeId();
        UUID r1AnswerId = branchAnswer.producedAnswerId();

        // Fork from the shared root: R2 becomes active.
        RouteMutationResponse fork = fork(project.id(), root.id());
        UUID r2RouteId = fork.route().id();

        int forkPoint = captured.size();
        AnswerRunView forkAnswer = submitAnswer(
                project.id(), "Fork branch answer local to R2");

        List<AgentRequestEnvelope> forkEnvelopes =
                captured.subList(forkPoint, captured.size());
        assertThat(forkEnvelopes).isNotEmpty();
        assertThat(forkAnswer.status()).isEqualTo("completed");

        for (AgentRequestEnvelope envelope : forkEnvelopes) {
            String text = envelopeText(envelope);
            assertThat(text)
                    .as("fork envelope must exclude sibling content")
                    .doesNotContain(SIBLING_SENTINEL)
                    .doesNotContain("node:" + nodeA.id())
                    .doesNotContain("node:" + nodeA2.id())
                    .doesNotContain("answer:" + r1AnswerId);
            assertThat(envelope.snapshot().routeId())
                    .as("fork envelopes target the active fork route")
                    .isEqualTo(r2RouteId);
            // Active lineage stays present: the shared root node and the
            // run-local answer text.
            assertThat(text)
                    .contains("node:" + root.id())
                    .contains("Fork branch answer local to R2");
        }

        // No sibling-route lineage entry leaked into any fork envelope.
        assertThat(forkEnvelopes)
                .allSatisfy(envelope -> assertThat(envelope.snapshot().lineage())
                        .allSatisfy(entry -> {
                            if (entry.node() != null) {
                                assertThat(entry.node().id())
                                        .isNotEqualTo(nodeA.id())
                                        .isNotEqualTo(nodeA2.id());
                            }
                            if (entry.answer() != null) {
                                assertThat(entry.answer().id()).isNotEqualTo(r1AnswerId);
                            }
                        }));
    }

    private record AnswerRunView(UUID runId, UUID routeId, UUID producedNodeId,
                                 UUID producedAnswerId, String status) {
    }

    /**
     * Enqueues an ANSWER_TIP run against the current active tip and drives it
     * through the worker; returns the run read view.
     */
    private AnswerRunView submitAnswer(UUID projectId, String freeText) throws Exception {
        MvcResult created = mockMvc.perform(
                        post("/api/v1/projects/{projectId}/agent-runs", projectId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"operation\": \"ANSWER_TIP\", \"freeText\": \""
                                        + freeText + "\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        String runId = extractString(created.getResponse().getContentAsString(), "runId");

        worker.executeRun(runService.claimNextAnswerCycle().orElseThrow());

        MvcResult read = mockMvc.perform(get("/api/v1/projects/{projectId}/agent-runs/{runId}",
                        projectId, runId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode view = objectMapper.readTree(read.getResponse().getContentAsString());
        return new AnswerRunView(
                UUID.fromString(view.get("runId").asText()),
                UUID.fromString(view.get("routeId").asText()),
                view.hasNonNull("producedNodeId") ? UUID.fromString(view.get("producedNodeId").asText()) : null,
                view.hasNonNull("producedAnswerId") ? UUID.fromString(view.get("producedAnswerId").asText()) : null,
                view.get("status").asText());
    }

    /** Reads the canonical node the completed run produced (test fixture read). */
    private NodeResponse nextNodeAfter(AnswerRunView view) {
        return NodeResponse.from(nodeService.getNode(view.producedNodeId()).orElseThrow());
    }

    private NodeResponse draftNext(UUID projectId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/projects/{projectId}/questions/next", projectId))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(result.getResponse().getContentAsString(), DraftQuestionResponse.class)
                .producedNode();
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
        return objectMapper.readValue(result.getResponse().getContentAsString(),
                RouteMutationResponse.class);
    }

    private String extractString(String json, String field) throws Exception {
        JsonNode node = objectMapper.readTree(json);
        return node.has(field) && !node.get(field).isNull() ? node.get(field).asText() : null;
    }
}
