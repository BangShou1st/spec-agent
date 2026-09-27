package com.specagent.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:E06SharedStateTest.java
 *
 * 测试目标:E06——共享状态(P2 语料)。变体 A(共享读)由生产路由隔离套件覆盖;
 * 此处工具链验证反向情形——变体 B:分叉路由试图对同一个权威 Question 给出分歧的
 * 第二个回答时,必须失败关闭(SHARED_STATE_DIVERGENCE),不得分叉权威状态、
 * 不得静默产生第二身份。
 */
class E06SharedStateTest extends EvalHarnessBase {

    @Test
    void divergentAnswerFailsClosedWithoutForkingCanonicalState() {
        ScenarioDefinition scenario = EvalCorpus.e06();
        VariantSpec variant = scenario.variants().get(0);

        ObservationEnvelope observation = runScenario(scenario, variant);

        assertThat(observation.violations())
                .as("E06 divergent violations: %s", observation.violations())
                .isEmpty();
        assertThat(observation.executionResult()).startsWith("failed:");
        assertThat(observation.executionResult()).contains("SHARED_STATE_DIVERGENCE");
        assertThat(observation.stateDelta()).containsEntry("answers", 0);
        assertThat(observation.stateDelta()).containsEntry("patches", 0);
        assertThat(observation.stateDelta()).containsEntry("nodes", 0);
    }

    @Test
    void divergentParaphraseVariantFailsClosed() {
        ScenarioDefinition scenario = EvalCorpus.e06();
        VariantSpec variant = scenario.variants().stream()
                .filter(v -> v.variantId().equals("divergent-paraphrase"))
                .findFirst()
                .orElseThrow();

        assertPasses(scenario, variant);
    }
}
