package com.specagent.eval;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 文件名:EvalHarnessBase.java
 *
 * 测试目标:评估套件共享基座。启动生产 Spring 上下文并接入脚本化 B-fast
 * 大脑,把场景跑过真实的生产回答循环,之后清理项目相关的数据行。
 *
 * 清理是手动的(不用 {@code @Transactional}),因为运行失败标记在自己
 * 的事务中提交——与既有的全链路集成测试保持一致。
 *
 * 清理范围必须覆盖**一个测试方法里跑过的每一个 project**,而不是最后一个:
 * 语料型用例(一次方法跑几十个场景)会为每个场景建一个新 project,只清最后一个
 * 会把其余 project 的非终态 {@code CONTINUE_CYCLE} run 留在库里。这些行会被
 * {@code AgentRunRepository.claimNextContinueRun} 以**全库范围**领到
 * ({@code ORDER BY created_at LIMIT 1}),从而在同库复跑时把续跑套件
 * ({@code ContinuationChainIntegrationTest} / {@code AgentRunLoopLinkageTest})
 * 引到别的 run 上——这与 P3-B 之前记录的那 17 例失败同根。
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

    /** 本测试方法里跑过的所有 project,方法结束统一清理。 */
    private final List<UUID> touchedProjectIds = new ArrayList<>();

    protected ObservationEnvelope runScenario(ScenarioDefinition scenario, VariantSpec variant) {
        ObservationEnvelope observation = scenarioRunner.run(scenario, variant);
        UUID projectId = scenarioRunner.lastProjectId();
        if (projectId != null) {
            touchedProjectIds.add(projectId);
        }
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
        for (UUID projectId : touchedProjectIds) {
            cleanUpProject(projectId);
        }
        touchedProjectIds.clear();
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
