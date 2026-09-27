package com.specagent.eval;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

/**
 * 文件名:EvalHarnessBase.java
 *
 * 测试目标:评估套件共享基座。启动生产 Spring 上下文并接入脚本化 B-fast
 * 大脑,把场景跑过真实的生产回答循环,之后清理项目相关的数据行。
 *
 * 清理是手动的(不用 {@code @Transactional}),因为运行失败标记在自己
 * 的事务中提交——与既有的全链路集成测试保持一致。
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
        cleanUpProject(activeProjectId);
        activeProjectId = null;
    }

    /** 供行为保持类测试清理中间对比运行。 */
    protected void cleanUpProject(UUID projectId) {
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
        // 路由通过 branch_at_node_id 引用节点,节点仅逻辑上引用路由,
        // 因此先删路由再删节点。
        jdbcTemplate.update("DELETE FROM routes WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM nodes WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM projects WHERE id = ?", projectId);
    }
}
