package com.specagent.api.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.agent.policy.AgentProposalService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.RouteRepository;
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

    @Test
    void proposedProposalListReturns200WithNullDecidedFields() throws Exception {
        Project project = projectService.createProject("提案列表测试");
        UUID routeId = routeRepository.findById(project.activeRouteId()).orElseThrow().id();
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
}
