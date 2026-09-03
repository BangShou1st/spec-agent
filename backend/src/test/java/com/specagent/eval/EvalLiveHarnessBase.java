package com.specagent.eval;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * P2 Phase 2 — Live behavioral baseline harness base.
 *
 * <p>Boots the production Spring context with the production Brain wiring
 * (remote-python engine through the internal inference broker) instead of the
 * scripted B-fast brain: {@code ScenarioRunner.runLive} refuses to run when a
 * {@code BrainScriptInstaller} bean is present, so importing only
 * {@code EvalProbeCapabilities.Config} here guarantees live observations can
 * never silently come from scripted outputs.
 *
 * <p>Live runs need a reachable agent-brain in broker mode and a configured
 * OpenCode provider behind the Java broker. When either is missing the suite
 * is skipped (non-blocking by construction) so PR CI stays green offline.
 * Cleanup mirrors {@link EvalHarnessBase} project-scoped row deletion because
 * run failure marking commits in its own transaction.
 */
@SpringBootTest
@ActiveProfiles("test")
@org.springframework.context.annotation.Import({EvalProbeCapabilities.Config.class})
public abstract class EvalLiveHarnessBase {

    /** Default repetitions per scenario variant for the stability baseline. */
    protected static final int LIVE_REPETITIONS = 3;

    @Autowired
    protected ScenarioRunner scenarioRunner;

    @Autowired
    protected JdbcTemplate jdbcTemplate;

    private final List<UUID> liveProjectIds = new ArrayList<>();

    /**
     * Runs one scenario variant N times through the live Brain and returns
     * every observation. Repetitions differ only by the recorded seed — never
     * by semantics — so unanimous vs mixed outcomes are the stability signal.
     */
    protected List<ObservationEnvelope> runLiveScenario(ScenarioDefinition scenario,
                                                        VariantSpec variant) {
        return runLiveScenario(scenario, variant, LIVE_REPETITIONS);
    }

    protected List<ObservationEnvelope> runLiveScenario(ScenarioDefinition scenario,
                                                        VariantSpec variant,
                                                        int repetitions) {
        List<ObservationEnvelope> observations = new ArrayList<>();
        for (int repetition = 0; repetition < repetitions; repetition++) {
            long repetitionSeed = variant.seed() * 1000L + repetition;
            ObservationEnvelope observation =
                    scenarioRunner.runLive(scenario, variant, repetitionSeed);
            liveProjectIds.add(scenarioRunner.lastProjectId());
            observations.add(observation);
        }
        return observations;
    }

    /** Skips the live suite unless the brain health endpoint is reachable. */
    protected void requireLiveBrain(String brainHealthUrl) {
        assumeTrue(brainReachable(brainHealthUrl),
                "agent-brain not reachable at " + brainHealthUrl
                        + " — start it in broker mode to run the live baseline");
    }

    private boolean brainReachable(String brainHealthUrl) {
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofMillis(1000)).build();
            java.net.http.HttpResponse<String> response = client.send(
                    java.net.http.HttpRequest.newBuilder(
                                    java.net.URI.create(brainHealthUrl))
                            .timeout(java.time.Duration.ofSeconds(3)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception ex) {
            return false;
        }
    }

    @AfterEach
    void cleanUpLiveProjects() {
        for (UUID projectId : liveProjectIds) {
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
            jdbcTemplate.update("DELETE FROM routes WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM nodes WHERE project_id = ?", projectId);
            jdbcTemplate.update("DELETE FROM projects WHERE id = ?", projectId);
        }
        liveProjectIds.clear();
    }
}
