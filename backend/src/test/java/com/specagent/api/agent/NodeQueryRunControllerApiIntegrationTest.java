package com.specagent.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.AgentRunStatus;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.policy.AgentProposalService;
import com.specagent.agent.policy.ProposalStatus;
import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Result API for a contextual node query run. Verifies the node-identity
 * guard (a run may only be served under the node it targeted) and that the
 * result view surfaces the advisory proposal a downgraded mutation action
 * produced for the run.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class NodeQueryRunControllerApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService commandService;
    @Autowired private RunService runService;
    @Autowired private RunWorker worker;
    @Autowired private AgentRunService agentRunService;
    @Autowired private AgentProposalService proposalService;
    @Autowired private RouteRepository routeRepository;

    private Project project;
    private UUID routeId;
    private Node nodeA;
    private Node nodeB;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("节点问答结果测试");
        routeId = routeRepository.findById(project.activeRouteId()).orElseThrow().id();
        nodeA = commandService.createRootDraftNode(
                project.id(), routeId, "REQUIREMENT", Map.of("text", "节点A"));
        nodeB = commandService.appendContinuation(
                        project.id(), routeId, nodeA.id(), "REQUIREMENT", Map.of("text", "节点B"))
                .node();
    }

    @Test
    void queryResultForWrongNodeReturns404() throws Exception {
        UUID runId = runService.createQueuedNodeQuery(
                project.id(), routeId, nodeA.id(), "A 的问题？");

        // The run belongs to nodeA; requesting it under nodeB must fail closed
        // with 404 rather than leaking another node's run result.
        mockMvc.perform(get("/api/v1/projects/{projectId}/nodes/{nodeId}/query/{runId}",
                        project.id(), nodeB.id(), runId))
                .andExpect(status().isNotFound());
    }

    @Test
    void queryResultExposesProposalForAwaitingApprovalRun() throws Exception {
        UUID runId = runService.createQueuedNodeQuery(
                project.id(), routeId, nodeA.id(), "A 的问题？");
        AgentRun claimed = runService.claimNextNodeQuery().orElseThrow();
        worker.executeRun(claimed);
        assertThat(agentRunService.getRun(runId).orElseThrow().status())
                .isEqualTo(AgentRunStatus.COMPLETED);

        // A node-query run that downgrades a mutation action to an approval
        // produces a proposal linked by runId; attach it the same way the
        // runtime would and confirm the result view exposes it.
        var proposal = proposalService.createProposal(
                new ActionProposal("CREATE_NODE",
                        Map.of("kind", "KNOWLEDGE"),
                        UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                        List.of(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                        List.of("node:" + nodeA.id())),
                runId, project.id(), routeId);

        mockMvc.perform(get("/api/v1/projects/{projectId}/nodes/{nodeId}/query/{runId}",
                        project.id(), nodeA.id(), runId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value(runId.toString()))
                .andExpect(jsonPath("$.status").value("AWAITING_APPROVAL"))
                .andExpect(jsonPath("$.proposalId").value(proposal.id().toString()))
                .andExpect(jsonPath("$.proposalStatus").value(ProposalStatus.PROPOSED.code()))
                .andExpect(jsonPath("$.actionFamily").value("CREATE_NODE"));
    }

    @Test
    void queryResultWithoutProposalHasNullProposalFields() throws Exception {
        UUID runId = runService.createQueuedNodeQuery(
                project.id(), routeId, nodeA.id(), "A 的问题？");

        MvcResult result = mockMvc.perform(get(
                        "/api/v1/projects/{projectId}/nodes/{nodeId}/query/{runId}",
                        project.id(), nodeA.id(), runId))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = new ObjectMapper().readTree(result.getResponse().getContentAsString());
        JsonNode proposalId = body.get("proposalId");
        JsonNode proposalStatus = body.get("proposalStatus");
        JsonNode actionFamily = body.get("actionFamily");
        // No proposal was produced for this run: the fields are present but null.
        assertThat(proposalId == null || proposalId.isNull()).isTrue();
        assertThat(proposalStatus == null || proposalStatus.isNull()).isTrue();
        assertThat(actionFamily == null || actionFamily.isNull()).isTrue();
    }
}
