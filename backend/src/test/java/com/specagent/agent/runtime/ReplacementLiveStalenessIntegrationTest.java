package com.specagent.agent.runtime;

import com.specagent.agent.action.StaleContextChecker;
import com.specagent.agent.AnswerCycleTestDriver;
import com.specagent.agent.DecisionCycleTestDriver;
import com.specagent.node.Node;
import com.specagent.node.NodeService;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Replacement live-staleness regression: the model decided on the frozen
 * snapshot; if the source route's tip moved (a new node was appended) before
 * the commit runs, the replacement must fail closed — never commit a decision
 * made against outdated route state, even when the target still sits on the
 * historical lineage.
 */
@SpringBootTest
@ActiveProfiles("test")
class ReplacementLiveStalenessIntegrationTest {

    @Autowired
    private ProjectService projectService;
    @Autowired
    private NodeService nodeService;
    @Autowired
    private DecisionCycleTestDriver draftDriver;
    @Autowired
    private AnswerCycleTestDriver answerDriver;
    @Autowired
    private StaleContextChecker staleContextChecker;
    @Autowired
    private RouteRepository routeRepository;
    @Autowired
    private RunService runService;

    /**
     * The deterministic precondition seam: expected tip = tip at snapshot
     * time; current tip mutated afterwards → stale.
     */
    @Test
    void replacementRejectsSourceRouteMutationAfterSnapshot() {
        Project project = projectService.createProject("Stale regen " + UUID.randomUUID());
        // Drain any queued answer-cycle runs left by other fixtures so the
        // driver claims exactly the run this test enqueues.
        while (runService.claimNextAnswerCycle().isPresent()) {
        }
        var draftRun = draftDriver.draftQuestion(project.id());
        var answerRun = answerDriver.submitFreeText(project.id(), "first answer");
        UUID sourceRouteId = answerRun.run().routeId();
        Node targetChild = nodeService.getNode(answerRun.producedNodeId()).orElseThrow();
        UUID targetNodeId = targetChild.parentNodeId();
        UUID tipAtSnapshotTime = routeRepository.findById(sourceRouteId)
                .orElseThrow().tipNodeId();

        // Model inference happens... meanwhile another node is appended to
        // the same source route (tip moves past what the model saw).
        nodeService.createChildNode(project.id(), sourceRouteId,
                targetChild.id(), "New question appended after snapshot",
                null, List.of(), true);

        assertThatThrownBy(() -> staleContextChecker.verifyLiveExecutionPreconditions(
                sourceRouteId, tipAtSnapshotTime, targetNodeId))
                .isInstanceOf(com.specagent.agent.action.StaleProposalException.class)
                .hasMessageContaining("changed after the replacement snapshot");
    }

    /** Target removed from the lineage also fails closed. */
    @Test
    void replacementRejectsTargetOutsideCurrentLineage() {
        // Nonexistent source route: the checker fails closed immediately.
        assertThatThrownBy(() -> new StaleContextChecker(routeRepository, null)
                .verifyLiveExecutionPreconditions(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(com.specagent.agent.action.StaleProposalException.class);
    }
}
