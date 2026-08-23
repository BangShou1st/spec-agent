package com.specagent.agent.policy;

import com.specagent.agent.contract.ActionProposal;
import com.specagent.graph.GraphCommandService;
import com.specagent.graph.GraphOperation;
import com.specagent.graph.NodeRelation;
import com.specagent.graph.NodeRelationRepository;
import com.specagent.graph.NodeRelationType;
import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Acceptance of Advisor proposals re-validates staleness against current
 * graph facts, executes through the command layer, and records the
 * acceptance in the typed operation log.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProposalAcceptanceIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService commandService;
    @Autowired private GraphCommandService graphCommandService;
    @Autowired private ProposalAcceptanceService acceptanceService;
    @Autowired private AgentProposalService proposalService;
    @Autowired private NodeRepository nodeRepository;
    @Autowired private RouteRepository routeRepository;
    @Autowired private NodeRelationRepository relationRepository;

    private Project project;
    private Route route;
    private Node tip;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("提案接受测试");
        route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        Node root = graphCommandService.createRootDraftNode(
                project.id(), route.id(), "NOTE", Map.of("text", "root"));
        tip = graphCommandService.appendContinuation(
                project.id(), route.id(), root.id(), "REQUIREMENT",
                Map.of("text", "tip 需求")).node();
    }

    private AgentProposal createPendingNodeProposal() {
        ActionProposal proposal = new ActionProposal(
                "CREATE_NODE",
                Map.of("kind", "KNOWLEDGE", "subtype", "RISK",
                        "content", Map.of("text", "离线同步可能产生冲突")),
                UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                List.of(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                List.of("node:" + tip.id()));
        return proposalService.createProposal(proposal, UUID.randomUUID(),
                project.id(), route.id());
    }

    @Test
    void acceptExecutesNodeCreationAndLogsOperation() {
        AgentProposal pending = createPendingNodeProposal();

        ProposalAcceptanceService.AcceptedProposalResult result =
                acceptanceService.acceptAndExecute(pending.id(), "user");

        assertThat(result.actionFamily()).isEqualTo("CREATE_NODE");
        assertThat(result.producedNodeId()).isNotNull();
        Node created = nodeRepository.findById(result.producedNodeId()).orElseThrow();
        assertThat(created.subtype()).isEqualTo("RISK");
        assertThat(created.authorKind().code()).isEqualTo("AGENT");

        assertThat(proposalService.getProposal(pending.id()).orElseThrow().status())
                .isEqualTo(ProposalStatus.ACCEPTED);

        assertThat(graphCommandService.listOperations(project.id()))
                .anySatisfy(op -> {
                    assertThat(op.type()).isEqualTo(GraphOperation.Type.ACCEPT_AGENT_PROPOSAL);
                    assertThat(op.actor()).isEqualTo(GraphOperation.Actor.AGENT);
                    assertThat(op.causedBy()).isEqualTo("proposal:" + pending.id());
                });
    }

    @Test
    void acceptRejectsStaleAnchorAndStaysPending() {
        AgentProposal pending = createPendingNodeProposal();

        // The route tip moves on before acceptance.
        graphCommandService.appendContinuation(
                project.id(), route.id(), tip.id(), "NOTE", Map.of("text", "new tip"));

        assertThatThrownBy(() -> acceptanceService.acceptAndExecute(pending.id(), "user"))
                .isInstanceOf(com.specagent.agent.action.StaleProposalException.class);

        assertThat(proposalService.getProposal(pending.id()).orElseThrow().status())
                .isEqualTo(ProposalStatus.PROPOSED);
    }

    @Test
    void acceptSemanticConnectCreatesRelationWithAgentProvenance() {
        Node root = nodeRepository.findById(tip.parentNodeId()).orElseThrow();
        ActionProposal proposal = new ActionProposal(
                "CONNECT_NODE",
                Map.of("relationClass", "SEMANTIC", "relationType", "DERIVED_FROM",
                        "sourceRef", "node:" + tip.id(), "targetRef", "node:" + root.id()),
                UUID.randomUUID(), "hash-" + UUID.randomUUID(),
                List.of(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                List.of());
        AgentProposal pending = proposalService.createProposal(
                proposal, UUID.randomUUID(), project.id(), route.id());

        ProposalAcceptanceService.AcceptedProposalResult result =
                acceptanceService.acceptAndExecute(pending.id(), "user");

        assertThat(result.relationId()).isNotNull();
        var relation = relationRepository.findById(result.relationId()).orElseThrow();
        assertThat(relation.relationType()).isEqualTo(NodeRelationType.DERIVED_FROM);
        assertThat(relation.origin()).isEqualTo(NodeRelation.Origin.AGENT);
        assertThat(relation.createdByProposalId()).isEqualTo(pending.id());
    }

    @Test
    void acceptTwiceIsRejected() {
        AgentProposal pending = createPendingNodeProposal();
        acceptanceService.acceptAndExecute(pending.id(), "user");

        assertThatThrownBy(() -> acceptanceService.acceptAndExecute(pending.id(), "user"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not pending acceptance");
    }
}
