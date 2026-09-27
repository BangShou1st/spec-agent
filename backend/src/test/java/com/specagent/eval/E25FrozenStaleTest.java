package com.specagent.eval;

import com.specagent.agent.runtime.ProposalAcceptanceService;
import com.specagent.workspace.node.NodeService;
import com.specagent.workspace.project.ProjectService;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:E25FrozenStaleTest.java
 *
 * 测试目标:E25——冻结/过期上下文(P2 语料)。base 用例证明 DECISION 基于
 * 后置状态冻结快照运行且无意外增量;stale 变体证明已撤回的上下文在验收边界
 * 失败关闭:大脑输出不能绕过 Java 校验,不发生任何意外变更。
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
