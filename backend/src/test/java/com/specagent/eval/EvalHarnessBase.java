package com.specagent.eval;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

/**
 * Shared harness base: boots the production Spring context with the
 * scripted B-fast brain, runs scenarios through the real production
 * answer cycle, and cleans up project-scoped rows afterwards.
 *
 * <p>Cleanup is manual (not {@code @Transactional}) because run failure
 * marking commits in its own transaction — mirroring the existing
 * full-loop integration tests.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import({ScriptedBrain.Config.class, EvalProbeCapabilities.Config.class})
public abstract class EvalHarnessBase {

    @Autowired
    protected ScenarioRunner scenarioRunner;

    @Autowired
    protected ScriptedBrain scriptedBrain;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    private UUID activeProjectId;

    protected ObservationEnvelope runScenario(ScenarioDefinition scenario, VariantSpec variant) {
        ObservationEnvelope observation = scenarioRunner.run(scenario, variant);
        activeProjectId = scenarioRunner.lastProjectId();
        return observation;
    }

    protected void assertPasses(ScenarioDefinition scenario, VariantSpec variant) {
        ObservationEnvelope observation = runScenario(scenario, variant);
        org.assertj.core.api.Assertions.assertThat(observation.violations())
                .as("scenario %s variant %s violations: %s",
                        scenario.scenarioId(), variant.variantId(),
                        describe(observation))
                .isEmpty();
    }

    private static String describe(ObservationEnvelope observation) {
        StringBuilder rendered = new StringBuilder();
        for (Violation violation : observation.violations()) {
            rendered.append("[").append(violation.failureClass()).append(": ")
                    .append(violation.detail()).append("] ");
        }
        rendered.append("action=").append(observation.actualPrimaryAction())
                .append(" result=").append(observation.executionResult());
        return rendered.toString();
    }

    @AfterEach
    void cleanUpEvalProject() {
        if (activeProjectId == null) {
            return;
        }
        UUID projectId = activeProjectId;
        jdbcTemplate.update(
                "DELETE FROM agent_run_events WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = ?)",
                projectId);
        jdbcTemplate.update("DELETE FROM agent_runs WHERE project_id = ?", projectId);
        jdbcTemplate.update(
                "DELETE FROM agent_input_projections WHERE snapshot_id IN (SELECT id FROM context_snapshots WHERE project_id = ?)",
                projectId);
        jdbcTemplate.update("DELETE FROM context_snapshots WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM agent_proposals WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM capability_invocations WHERE project_id = ?", projectId);
        jdbcTemplate.update(
                "DELETE FROM route_inherited_answers WHERE branch_route_id IN (SELECT id FROM routes WHERE project_id = ?)",
                projectId);
        jdbcTemplate.update(
                "DELETE FROM route_inherited_answers WHERE answer_id IN (SELECT id FROM answers WHERE project_id = ?)",
                projectId);
        jdbcTemplate.update("DELETE FROM answer_patches WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM answers WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM node_relations WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM graph_operations WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM spec_snapshots WHERE project_id = ?", projectId);
        // Routes reference nodes (branch_at_node_id) and nodes reference
        // routes only logically, so routes go before nodes.
        jdbcTemplate.update("DELETE FROM routes WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM nodes WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", projectId);
        activeProjectId = null;
    }
}
