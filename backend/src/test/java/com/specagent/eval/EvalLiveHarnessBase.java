package com.specagent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.provider.OpenCodeZenTransport;
import com.specagent.settings.opencode.OpenCodeSettingsService;
import com.specagent.settings.opencode.RuntimeOpenCodeSettings;
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
 * <p>Live runs need explicit external OpenCode settings and a reachable
 * agent-brain in broker mode. Missing/invalid provider settings fail closed;
 * an unavailable brain remains an environmental skip so PR CI stays green
 * offline.
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

    @Autowired
    protected ObjectMapper objectMapper;

    @Autowired
    protected OpenCodeSettingsService openCodeSettingsService;

    @Autowired
    protected OpenCodeZenTransport openCodeZenTransport;

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
            observation = observation.withRepetition(repetition);
            liveProjectIds.add(scenarioRunner.lastProjectId());
            observations.add(observation);
        }
        return observations;
    }

    /**
     * Requires a real live chain and returns safe evidence stamped into the
     * baseline artifact. Provider configuration is checked first and is a
     * hard failure: a live run must never skip or fall back when its explicit
     * external configuration is missing or invalid.
     */
    protected LiveChainEvidence requireLiveBrain(String brainHealthUrl) {
        RuntimeOpenCodeSettings settings;
        try {
            settings = openCodeSettingsService.requireRuntimeSettings();
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "B-live rejected before baseline: live provider configuration is "
                            + "missing or invalid; " + ex.getMessage(), ex);
        }
        LiveExecutionGuard.Evidence javaWiring = scenarioRunner.requireLiveWiring();
        LiveBrainHealth health = readLiveBrainHealth(brainHealthUrl);
        return new LiveChainEvidence(javaWiring, health, settings.selectedModel(),
                settings.credentialSource(), openCodeZenTransport.endpoint());
    }

    /** Reads safe Python-side invocation evidence; no prompt or completion data. */
    protected LiveBrainHealth readLiveBrainHealth(String brainHealthUrl) {
        java.net.http.HttpResponse<String> response;
        try {
            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(java.time.Duration.ofMillis(1000)).build();
            response = client.send(
                    java.net.http.HttpRequest.newBuilder(
                                    java.net.URI.create(brainHealthUrl))
                            .timeout(java.time.Duration.ofSeconds(3)).GET().build(),
                    java.net.http.HttpResponse.BodyHandlers.ofString());
        } catch (Exception ex) {
            assumeTrue(false, "agent-brain not reachable at " + brainHealthUrl
                    + " — start it in broker mode to run the live baseline");
            return null;
        }

        assumeTrue(response.statusCode() == 200,
                "agent-brain health returned HTTP " + response.statusCode()
                        + " at " + brainHealthUrl);

        JsonNode body;
        try {
            body = objectMapper.readTree(response.body());
        } catch (Exception ex) {
            assumeTrue(false, "agent-brain health was not valid JSON at " + brainHealthUrl);
            return null;
        }
        String protocol = body.path("protocolVersion").asText("");
        String modelMode = body.path("modelMode").asText("");
        assumeTrue("agent-input.v2".equals(protocol),
                "agent-brain protocol is " + protocol + ", expected agent-input.v2");
        assumeTrue("broker".equals(modelMode),
                "agent-brain reports modelMode=" + modelMode
                        + "; fake model mode is not B-live");
        JsonNode invocations = body.path("invocations");
        assumeTrue(invocations.isObject()
                        && invocations.has("stateUpdates")
                        && invocations.has("decisions"),
                "agent-brain health lacks invocation evidence; refusing to label B-live");
        return new LiveBrainHealth(
                protocol,
                modelMode,
                invocations.path("stateUpdates").asInt(),
                invocations.path("decisions").asInt(),
                nullableText(invocations.path("lastStateUpdateRunId")),
                nullableText(invocations.path("lastDecisionRunId")));
    }

    private static String nullableText(JsonNode node) {
        return node.isTextual() ? node.asText() : null;
    }

    protected record LiveBrainHealth(String protocolVersion,
                                     String modelMode,
                                     int stateUpdates,
                                     int decisions,
                                     String lastStateUpdateRunId,
                                     String lastDecisionRunId) {
    }

    protected record LiveChainEvidence(LiveExecutionGuard.Evidence javaWiring,
                                       LiveBrainHealth pythonBefore,
                                       String selectedModel,
                                       String credentialSource,
                                       String endpoint) {

        LiveChainEvidence withPythonAfter(LiveBrainHealth pythonAfter) {
            return new LiveChainEvidence(javaWiring,
                    new LiveBrainHealth(
                            pythonBefore.protocolVersion(),
                            pythonBefore.modelMode(),
                            pythonAfter.stateUpdates() - pythonBefore.stateUpdates(),
                            pythonAfter.decisions() - pythonBefore.decisions(),
                            pythonAfter.lastStateUpdateRunId(),
                            pythonAfter.lastDecisionRunId()),
                    selectedModel, credentialSource, endpoint);
        }
    }

    @AfterEach
    void cleanUpLiveProjects() {
        for (UUID projectId : liveProjectIds) {
            jdbcTemplate.update(
                    "DELETE FROM agent_run_events WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = ?)",
                    projectId);
            jdbcTemplate.update(
                    "DELETE FROM agent_run_continuation_checks WHERE run_id IN (SELECT id FROM agent_runs WHERE project_id = ?)",
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
