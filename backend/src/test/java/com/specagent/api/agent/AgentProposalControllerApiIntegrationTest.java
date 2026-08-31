package com.specagent.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.policy.AgentProposalService;
import com.specagent.graph.GraphCommandService;
import com.specagent.node.Node;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Advisor proposal listing. A PROPOSED proposal has not been decided yet, so
 * its {@code decidedAt}/{@code decidedBy} are null; the summary must serialize
 * them as null (never throw) and the default {@code /proposals} list must
 * return 200, not 500.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AgentProposalControllerApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectService projectService;
    @Autowired private AgentProposalService proposalService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private RunService runService;
    @Autowired private AgentRunService agentRunService;
    @Autowired private GraphCommandService commandService;

    private Project project;
    private UUID routeId;
    private Node anchor;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("提案列表测试 " + UUID.randomUUID());
        routeId = routeRepository.findById(project.activeRouteId()).orElseThrow().id();
        anchor = commandService.createRootDraftNode(
                project.id(), routeId, "REQUIREMENT", Map.of("text", "锚点"));
    }

    @Test
    void proposedProposalListReturns200WithNullDecidedFields() throws Exception {
        proposalService.createProposal(
                new ActionProposal("CREATE_NODE",
                        Map.of("kind", "KNOWLEDGE"),
                        UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                        List.of(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                        List.of()),
                UUID.randomUUID(), project.id(), routeId);

        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/proposals",
                        project.id()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        assertThat(body).hasSize(1);
        JsonNode summary = body.get(0);
        assertThat(summary.get("status").asText()).isEqualTo("PROPOSED");
        // PROPOSED proposals are undecided: decidedAt/decidedBy must be null.
        // Before the LinkedHashMap fix, Map.of(...) with a null value threw
        // NullPointerException and the list returned HTTP 500.
        JsonNode decidedAt = summary.get("decidedAt");
        JsonNode decidedBy = summary.get("decidedBy");
        assertThat(decidedAt == null || decidedAt.isNull()).isTrue();
        assertThat(decidedBy == null || decidedBy.isNull()).isTrue();
    }

    /**
     * Item 8 (deep review) — the pending proposal list carries enough runtime
     * identity (runId + inputNodeId) for the frontend to reconnect a durable
     * PROPOSED proposal to its NodeQuery anchor node after a page reload.
     */
    @Test
    void proposalSummaryCarriesRuntimeIdentityForReloadReconnection() throws Exception {
        UUID runId = runService.createQueuedNodeQuery(
                project.id(), routeId, anchor.id(), "锚点问题？");
        // Bind a real node-query run to the proposal so the anchor resolves.
        var proposal = proposalService.createProposal(
                new ActionProposal("CREATE_NODE",
                        Map.of("kind", "KNOWLEDGE", "subtype", "RISK",
                                "content", Map.of("text", "结论")),
                        UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                        List.of(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                        List.of()),
                runId, project.id(), routeId);

        MvcResult result = mockMvc.perform(get("/api/v1/projects/{projectId}/proposals",
                        project.id()))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        assertThat(body).hasSize(1);
        JsonNode summary = body.get(0);
        // Reconnection identity: proposalId, runId, and the canonical anchor
        // node id (inputNodeId) of the query run that produced the proposal.
        assertThat(summary.get("proposalId").asText()).isEqualTo(proposal.id().toString());
        assertThat(summary.get("runId").asText()).isEqualTo(runId.toString());
        assertThat(summary.get("inputNodeId").asText()).isEqualTo(anchor.id().toString());
        assertThat(summary.get("routeId").asText()).isEqualTo(routeId.toString());
        assertThat(summary.get("actionFamily").asText()).isEqualTo("CREATE_NODE");
        assertThat(summary.get("status").asText()).isEqualTo("PROPOSED");
    }
}
