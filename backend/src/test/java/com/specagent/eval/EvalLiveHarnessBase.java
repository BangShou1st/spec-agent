package com.specagent.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.RuntimeOpenCodeSettings;
import com.specagent.model.provider.OpenCodeZenTransport;
import com.specagent.modelsettings.OpenCodeSettingsService;
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
 * 文件名:EvalLiveHarnessBase.java
 *
 * 测试目标:P2 Phase 2——live 行为基线套件的共享基座。
 *
 * 启动生产 Spring 上下文,使用生产大脑接线(经内部推理 broker 的
 * remote-python 引擎)而非脚本化 B-fast 大脑:{@code ScenarioRunner.runLive}
 * 在存在 {@code BrainScriptInstaller} bean 时拒绝运行,因此这里只引入
 * {@code EvalProbeCapabilities.Config},保证 live 观测绝不可能静默来自脚本输出。
 *
 * live 运行需要显式的外部 OpenCode 配置和可达的 broker 模式 agent-brain。
 * Provider 配置缺失/无效时失败关闭;大脑不可用则作为环境性跳过,
 * 使 PR CI 离线仍保持绿色。清理逻辑与 {@link EvalHarnessBase} 的项目级行删除
 * 相同,因为运行失败标记在自己事务中提交。
 */
@SpringBootTest
@ActiveProfiles("test")
@org.springframework.context.annotation.Import({EvalProbeCapabilities.Config.class})
public abstract class EvalLiveHarnessBase {

    /** 稳定性基线中每个场景变体的默认重复次数。 */
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
     * 把一个场景变体经 live 大脑运行 N 次并返回全部观测。各次重复只在记录的
     * seed 上不同——语义完全一致——因此"全体一致 vs 结果混杂"即是稳定性信号。
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
     * 要求真实 live 链路并返回写入基线产物的安全证据。先检查 Provider 配置,
     * 且属于硬失败:显式外部配置缺失或无效时,live 运行绝不能跳过或回退。
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

    /** 读取 Python 侧的安全调用证据;不含任何 prompt 或补全数据。 */
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
