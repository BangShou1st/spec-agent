package com.specagent.eval;

import com.specagent.agent.policy.ProposalAcceptanceService;
import com.specagent.node.NodeService;
import com.specagent.project.ProjectService;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * E25 — Frozen/stale context (P2 corpus).
 *
 * <p>The base attempt proves the DECISION runs against the post-state
 * frozen snapshot with no unexpected delta. The stale variant proves
 * retracted context fails closed at the acceptance boundary: no Brain
 * output can bypass Java validation, and no unintended mutation occurs.
 */
class E25FrozenStaleTest extends EvalHarnessBase {

    @Autowired
    private ProposalAcceptanceService acceptanceService;

    @Autowired
    private NodeService nodeService;

    @Autowired
    private RouteRepository routeRepository;

    @Autowired
    private ProjectService projectService;

    @Test
    void frozenReplayCompletesWithPostStateSnapshot() {
        ScenarioDefinition scenario = EvalCorpus.e25();
        VariantSpec variant = scenario.variants().get(0);

        ObservationEnvelope observation = runScenario(scenario, variant);

        assertThat(observation.violations())
                .as("E25 frozen violations: %s", observation.violations())
                .isEmpty();
        assertThat(observation.contextSnapshotHash()).isNotNull();
        assertThat(observation.actualPrimaryAction()).isEqualTo("REQUEST_USER_INPUT");
    }

    @Test
    void shuffledVariantCompletesWithPostStateSnapshot() {
        ScenarioDefinition scenario = EvalCorpus.e25();
        VariantSpec variant = scenario.variants().stream()
                .filter(v -> v.variantId().equals("stale-relation-set"))
                .findFirst()
                .orElseThrow();

        ObservationEnvelope observation = runScenario(scenario, variant);

        assertThat(observation.violations())
                .as("E25 shuffled violations: %s", observation.violations())
                .isEmpty();
        assertThat(observation.contextSnapshotHash()).isNotNull();
    }

    @Test
    void staleAcceptanceFailsClosedWithoutMutation() {
        ScenarioDefinition scenario = EvalCorpus.e25Stale();
        ObservationEnvelope observation = runScenario(scenario, scenario.variants().get(0));

        assertThat(observation.violations())
                .as("E25 stale setup violations: %s", observation.violations())
                .isEmpty();
        assertThat(observation.executionResult()).startsWith("awaiting_approval:");
        UUID proposalId = UUID.fromString(
                observation.executionResult().substring("awaiting_approval:".length()));
        UUID projectId = scenarioRunner.lastProjectId();
        int nodesBefore = nodeService.listProject(projectId).size();

        retractTip(projectId);

        assertThatThrownBy(() -> acceptanceService.acceptAndExecute(proposalId, "eval-test"))
                .isInstanceOf(RuntimeException.class);

        assertThat(nodeService.listProject(projectId)).hasSize(nodesBefore);
    }

    private void retractTip(UUID projectId) {
        UUID activeRouteId = projectService.getProject(projectId).orElseThrow().activeRouteId();
        Route route = routeRepository.findById(activeRouteId).orElseThrow();
        nodeService.setRetracted(route.tipNodeId(), true);
    }
}
