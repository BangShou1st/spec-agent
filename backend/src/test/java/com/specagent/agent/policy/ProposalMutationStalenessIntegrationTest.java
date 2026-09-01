package com.specagent.agent.policy;

import com.specagent.agent.action.StaleProposalException;
import com.specagent.agent.contract.ActionProposal;
import com.specagent.context.ContextBuilder;
import com.specagent.context.ContextSnapshot;
import com.specagent.graph.GraphCommandService;
import com.specagent.node.Node;
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

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProposalMutationStalenessIntegrationTest {

    @Autowired private ProjectService projectService;
    @Autowired private GraphCommandService graphCommandService;
    @Autowired private RouteRepository routeRepository;
    @Autowired private ContextBuilder contextBuilder;
    @Autowired private com.specagent.agent.snapshot.AgentInputSnapshotBuilder snapshotBuilder;
    @Autowired private com.specagent.agent.action.StaleContextChecker staleContextChecker;
    @Autowired private ProposalAcceptanceService acceptanceService;
    @Autowired private AgentProposalService proposalService;

    private Project project;
    private Route route;

    @BeforeEach
    void setUp() {
        project = projectService.createProject("staleness-" + UUID.randomUUID());
        route = routeRepository.findById(project.activeRouteId()).orElseThrow();
    }

    private Node createDraft(String text) {
        return graphCommandService.createRootDraftNode(project.id(), route.id(), "NOTE", Map.of("text", text));
    }

    private ActionProposal mutatingProposal(ContextSnapshot snapshot, String label) {
        return new ActionProposal(
                "CREATE_NODE",
                Map.of("kind", "KNOWLEDGE", "subtype", "NOTE", "content", Map.of("text", label)),
                snapshot.id(), snapshot.contextHash(),
                List.of(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                List.of("node:" + snapshot.tipNodeId()));
    }

    private ActionProposal readOnlyProposal(ContextSnapshot snapshot) {
        return new ActionProposal(
                "RESPOND_TO_USER",
                Map.of("message", "hello"),
                snapshot.id(), snapshot.contextHash(),
                List.of(), UUID.randomUUID(), "idem-" + UUID.randomUUID(),
                List.of("node:" + snapshot.tipNodeId()));
    }

    private void freeze(ContextSnapshot snapshot) {
        snapshotBuilder.build(snapshot);
    }

    @Test
    void t9_lineageSourceMutationMakesMutatingProposalStale() {
        Node draft = createDraft("before");
        ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(project.id(), route.id(), draft.id(), "q1");
        freeze(snapshot);
        ActionProposal proposal = mutatingProposal(snapshot, "new child");
        graphCommandService.reviseDraftNode(project.id(), draft.id(), "NOTE", Map.of("text", "after"));
        var ctx = new com.specagent.agent.action.ActionExecutionContext(
                UUID.randomUUID(), project.id(), route.id(), snapshot.id(), draft.id(), null, null);
        assertThatThrownBy(() -> staleContextChecker.check(proposal, ctx, snapshot))
                .isInstanceOf(StaleProposalException.class)
                .hasMessageContaining("stale");
    }

    @Test
    void t10_relatedSourceMutationMakesMutatingProposalStale() {
        Node anchor = graphCommandService.createRootDraftNode(project.id(), route.id(), "NOTE", Map.of("text", "anchor"));
        Node related = graphCommandService.createFloatingDraftNode(project.id(), null, "NOTE", Map.of("text", "before"));
        graphCommandService.createSemanticRelation(project.id(), anchor.id(), related.id(),
                com.specagent.graph.NodeRelationType.RELATED_TO, com.specagent.graph.NodeRelation.Origin.USER, null, null);
        ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(project.id(), route.id(), anchor.id(), "q-related");
        freeze(snapshot);
        ActionProposal proposal = mutatingProposal(snapshot, "child after related");
        graphCommandService.reviseDraftNode(project.id(), related.id(), "NOTE", Map.of("text", "after"));
        var ctx = new com.specagent.agent.action.ActionExecutionContext(
                UUID.randomUUID(), project.id(), route.id(), snapshot.id(), anchor.id(), null, null);
        assertThatThrownBy(() -> staleContextChecker.check(proposal, ctx, snapshot))
                .isInstanceOf(StaleProposalException.class);
    }

    @Test
    void t11_unrelatedMutationDoesNotMakeProposalStale() {
        Node draft = createDraft("visible");
        Node unrelated = graphCommandService.createFloatingDraftNode(project.id(), null, "NOTE", Map.of("text", "unrelated-before"));
        ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(project.id(), route.id(), draft.id(), "q-unrelated");
        freeze(snapshot);
        ActionProposal proposal = mutatingProposal(snapshot, "child");
        graphCommandService.reviseDraftNode(project.id(), unrelated.id(), "NOTE", Map.of("text", "unrelated-after"));
        var ctx = new com.specagent.agent.action.ActionExecutionContext(
                UUID.randomUUID(), project.id(), route.id(), snapshot.id(), draft.id(), null, null);
        staleContextChecker.check(proposal, ctx, snapshot);
    }

    @Test
    void t12_frozenReplayStillFrozenWhileMutatingProposalIsStale() {
        Node draft = createDraft("A");
        ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(project.id(), route.id(), draft.id(), "q12");
        var first = snapshotBuilder.build(snapshot);
        assertThat(first.lineage().get(0).node().body().text()).isEqualTo("A");
        ActionProposal proposal = mutatingProposal(snapshot, "child12");
        graphCommandService.reviseDraftNode(project.id(), draft.id(), "NOTE", Map.of("text", "B"));
        var replayed = snapshotBuilder.build(snapshot);
        assertThat(replayed.lineage().get(0).node().body().text()).isEqualTo("A");
        assertThat(replayed).isEqualTo(first);
        var ctx = new com.specagent.agent.action.ActionExecutionContext(
                UUID.randomUUID(), project.id(), route.id(), snapshot.id(), draft.id(), null, null);
        assertThatThrownBy(() -> staleContextChecker.check(proposal, ctx, snapshot))
                .isInstanceOf(StaleProposalException.class);
        var ro = readOnlyProposal(snapshot);
        staleContextChecker.check(ro, ctx, snapshot);
    }

    @Test
    void acceptanceRejectsStaleFingerprintedProposal() {
        Node draft = createDraft("before-accept");
        ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(project.id(), route.id(), draft.id(), "q-accept");
        freeze(snapshot);
        ActionProposal proposal = mutatingProposal(snapshot, "accepted child");
        var stored = proposalService.createProposal(proposal, UUID.randomUUID(), project.id(), route.id());
        graphCommandService.reviseDraftNode(project.id(), draft.id(), "NOTE", Map.of("text", "after-accept"));
        assertThatThrownBy(() -> acceptanceService.acceptAndExecute(stored.id(), "tester"))
                .isInstanceOf(StaleProposalException.class);
        assertThat(proposalService.getProposal(stored.id()).orElseThrow().status().code()).isEqualTo("PROPOSED");

    }

    @Test
    void acceptanceAllowsReadOnlyProposalEvenAfterDrift() {
        Node draft = createDraft("before-ro");
        ContextSnapshot snapshot = contextBuilder.buildForNodeQuery(project.id(), route.id(), draft.id(), "q-ro");
        freeze(snapshot);
        ActionProposal proposal = readOnlyProposal(snapshot);
        var stored = proposalService.createProposal(proposal, UUID.randomUUID(), project.id(), route.id());
        graphCommandService.reviseDraftNode(project.id(), draft.id(), "NOTE", Map.of("text", "after-ro"));
        assertThatThrownBy(() -> acceptanceService.acceptAndExecute(stored.id(), "tester"))
                .isNotInstanceOf(StaleProposalException.class);
    }
}