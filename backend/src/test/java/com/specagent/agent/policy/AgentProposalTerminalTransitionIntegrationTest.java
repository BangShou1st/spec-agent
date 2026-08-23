package com.specagent.agent.policy;

import com.specagent.agent.contract.ActionProposal;
import com.specagent.graph.GraphCommandService;
import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Sequential protection of the proposal terminal lifecycle: every illegal
 * transition out of a decided state fails with
 * {@link ProposalAlreadyDecidedException} and leaves the winning decision
 * untouched. Guards against regressing the repository back to an
 * unconditional {@code UPDATE ... WHERE id = :id}.
 */
@SpringBootTest
@ActiveProfiles("test")
class AgentProposalTerminalTransitionIntegrationTest {

    @Autowired private AgentProposalService proposalService;
    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService graphCommandService;
    @Autowired private NodeRepository nodeRepository;
    @Autowired private RouteRepository routeRepository;
    @Autowired private JdbcTemplate jdbcTemplate;

    private Project project;
    private Route route;
    private Node tip;

    @BeforeEach
    void setUp() {
        project = projectService.createProject(
                "提案终态转换测试-" + UUID.randomUUID());
        route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        Node root = graphCommandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "root"));
        tip = graphCommandService.appendContinuation(
                project.id(), route.id(), root.id(), "REQUIREMENT",
                Map.of("text", "tip 需求")).node();
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM agent_proposals WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM graph_operations WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM node_relations WHERE project_id = ?", project.id());
        jdbcTemplate.update("DELETE FROM nodes WHERE project_id = ?", project.id());
    }

    private AgentProposal createPendingNodeProposal() {
        ActionProposal proposal = new ActionProposal(
                "CREATE_NODE",
                Map.of("kind", "KNOWLEDGE", "subtype", "RISK",
                        "content", Map.of("text", "离线同步可能产生冲突")),
                UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                List.of(), UUID.randomUUID(), "idem-term-" + UUID.randomUUID(),
                List.of("node:" + tip.id()));
        return proposalService.createProposal(proposal, UUID.randomUUID(),
                project.id(), route.id());
    }

    private void assertStillDecided(UUID proposalId, ProposalStatus expected,
                                    String expectedDecidedBy) {
        AgentProposal current = proposalService.getProposal(proposalId).orElseThrow();
        assertThat(current.status()).isEqualTo(expected);
        assertThat(current.decidedBy()).isEqualTo(expectedDecidedBy);
        assertThat(current.decidedAt()).isNotNull();
    }

    @Test
    void acceptedProposalCannotBeRejected() {
        AgentProposal pending = createPendingNodeProposal();
        proposalService.acceptProposal(pending.id(), "user");

        assertThatThrownBy(() -> proposalService.rejectProposal(pending.id(), "attacker"))
                .isInstanceOf(ProposalAlreadyDecidedException.class)
                .extracting(e -> ((ProposalAlreadyDecidedException) e).currentStatus())
                .isEqualTo("ACCEPTED");

        assertStillDecided(pending.id(), ProposalStatus.ACCEPTED, "user");
    }

    @Test
    void acceptedProposalCannotBeExpired() {
        AgentProposal pending = createPendingNodeProposal();
        proposalService.acceptProposal(pending.id(), "user");

        assertThatThrownBy(() -> proposalService.expireProposal(pending.id()))
                .isInstanceOf(ProposalAlreadyDecidedException.class)
                .extracting(e -> ((ProposalAlreadyDecidedException) e).currentStatus())
                .isEqualTo("ACCEPTED");

        assertStillDecided(pending.id(), ProposalStatus.ACCEPTED, "user");
    }

    @Test
    void rejectedProposalCannotBeAccepted() {
        AgentProposal pending = createPendingNodeProposal();
        proposalService.rejectProposal(pending.id(), "user");

        assertThatThrownBy(() -> proposalService.acceptProposal(pending.id(), "late-comer"))
                .isInstanceOf(ProposalAlreadyDecidedException.class)
                .extracting(e -> ((ProposalAlreadyDecidedException) e).currentStatus())
                .isEqualTo("REJECTED");

        assertStillDecided(pending.id(), ProposalStatus.REJECTED, "user");
    }

    @Test
    void rejectedProposalCannotBeExpired() {
        AgentProposal pending = createPendingNodeProposal();
        proposalService.rejectProposal(pending.id(), "user");

        assertThatThrownBy(() -> proposalService.expireProposal(pending.id()))
                .isInstanceOf(ProposalAlreadyDecidedException.class);

        assertStillDecided(pending.id(), ProposalStatus.REJECTED, "user");
    }

    @Test
    void expiredProposalCannotBeAccepted() {
        AgentProposal pending = createPendingNodeProposal();
        proposalService.expireProposal(pending.id());

        assertThatThrownBy(() -> proposalService.acceptProposal(pending.id(), "late-comer"))
                .isInstanceOf(ProposalAlreadyDecidedException.class)
                .extracting(e -> ((ProposalAlreadyDecidedException) e).currentStatus())
                .isEqualTo("EXPIRED");

        assertStillDecided(pending.id(), ProposalStatus.EXPIRED, "system");
    }

    @Test
    void expiredProposalCannotBeRejected() {
        AgentProposal pending = createPendingNodeProposal();
        proposalService.expireProposal(pending.id());

        assertThatThrownBy(() -> proposalService.rejectProposal(pending.id(), "attacker"))
                .isInstanceOf(ProposalAlreadyDecidedException.class);

        assertStillDecided(pending.id(), ProposalStatus.EXPIRED, "system");
    }
}
