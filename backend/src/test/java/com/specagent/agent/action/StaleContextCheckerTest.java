package com.specagent.agent.action;

import com.specagent.agent.contract.ActionProposal;
import com.specagent.context.ContextSnapshot;
import com.specagent.context.ContextOperationType;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StaleContextCheckerTest {

    @Autowired
    private StaleContextChecker checker;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private RouteRepository routeRepository;

    @Test
    void validContextPasses() {
        TestContext tc = setupWithTipNode();
        ActionProposal proposal = proposal(tc.snapshotId, tc.hash, tc.tipNodeId);
        ActionExecutionContext context = context(tc);

        assertThatCode(() -> checker.check(proposal, context, tc.snapshot))
                .doesNotThrowAnyException();
    }

    @Test
    void staleHashIsRejected() {
        TestContext tc = setupWithTipNode();
        ActionProposal proposal = proposal(tc.snapshotId, "old_hash", tc.tipNodeId);
        ActionExecutionContext context = context(tc);

        assertThatThrownBy(() -> checker.check(proposal, context, tc.snapshot))
                .isInstanceOf(StaleProposalException.class)
                .hasMessageContaining("stale");
    }

    @Test
    void staleSnapshotIdIsRejected() {
        TestContext tc = setupWithTipNode();
        ActionProposal proposal = proposal(UUID.randomUUID(), tc.hash, tc.tipNodeId);
        ActionExecutionContext context = context(tc);

        assertThatThrownBy(() -> checker.check(proposal, context, tc.snapshot))
                .isInstanceOf(StaleProposalException.class)
                .hasMessageContaining("snapshot");
    }

    @Test
    void staleAnchorIsRejected() {
        TestContext tc = setupWithTipNode();
        UUID staleAnchor = UUID.randomUUID();
        ActionProposal proposal = proposal(tc.snapshotId, tc.hash, staleAnchor);
        ActionExecutionContext context = context(tc);

        assertThatThrownBy(() -> checker.check(proposal, context, tc.snapshot))
                .isInstanceOf(StaleProposalException.class)
                .hasMessageContaining("anchor");
    }

    private TestContext setupWithTipNode() {
        Project project = projectService.createProject("Stale 测试项目");
        Route route = routeRepository.findById(project.activeRouteId()).orElseThrow();
        Node root = nodeService.createRootNode(project.id(), route.id(),
                "根节点", null, List.of(), true);
        // Re-read route after node creation to get the updated tipNodeId.
        Route updatedRoute = routeRepository.findById(route.id()).orElseThrow();
        UUID snapshotId = UUID.randomUUID();
        String hash = "hash";
        ContextSnapshot snapshot = new ContextSnapshot(
                snapshotId, project.id(), updatedRoute.id(), updatedRoute.tipNodeId(),
                ContextOperationType.NORMAL, List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), null, hash, Instant.now());
        return new TestContext(project.id(), updatedRoute.id(),
                updatedRoute.tipNodeId(), snapshotId, hash, snapshot);
    }

    private ActionProposal proposal(UUID snapshotId, String hash, UUID anchorNodeId) {
        return new ActionProposal(
                "REQUEST_USER_INPUT", Map.of(),
                snapshotId, hash, List.of(),
                UUID.randomUUID(), "idemp-1",
                List.of("node:" + anchorNodeId));
    }

    private ActionExecutionContext context(TestContext tc) {
        return new ActionExecutionContext(
                UUID.randomUUID(), tc.projectId, tc.routeId,
                UUID.randomUUID(), tc.tipNodeId, null, null);
    }

    private record TestContext(UUID projectId, UUID routeId, UUID tipNodeId,
                               UUID snapshotId, String hash, ContextSnapshot snapshot) {
    }
}
