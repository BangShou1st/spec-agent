package com.specagent.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:E01SimpleAnswerTest.java
 *
 * 测试目标:E01——简单回答(smoke 场景,P2 语料)。验证最基础的生产回答循环:
 * Answer 持久化、STATE_UPDATE 补丁通过 Java 校验与应用、后置状态的 ContextSnapshot
 * 供 DECISION 使用、主动作被允许、无关状态未被改动、调用预算不超支。
 * 覆盖 base、paraphrase(同义改写)、shuffled(上下文乱序)三个变体。
 */
class E01SimpleAnswerTest extends EvalHarnessBase {

    @Test
    void baseVariantPassesFullCycle() {
        ScenarioDefinition scenario = EvalCorpus.e01();
        VariantSpec variant = scenario.variants().get(0);

        ObservationEnvelope observation = runScenario(scenario, variant);

        assertThat(observation.passed()).isTrue();
        assertThat(observation.actualPrimaryAction()).isEqualTo("REQUEST_USER_INPUT");
        assertThat(observation.productionModelCalls()).isEqualTo(2);
        assertThat(observation.providerRetries()).isZero();
        assertThat(observation.stateDelta()).containsEntry("answers", 1);
        assertThat(observation.stateDelta()).containsEntry("patches", 1);
    }

    @Test
    void paraphrasedVariantPassesFullCycle() {
        ScenarioDefinition scenario = EvalCorpus.e01();
        VariantSpec variant = scenario.variants().stream()
                .filter(v -> v.variantId().equals("paraphrase"))
                .findFirst()
                .orElseThrow();

        assertPasses(scenario, variant);
    }

    @Test
    void shuffledContextVariantPassesFullCycle() {
        ScenarioDefinition scenario = EvalCorpus.e01();
        VariantSpec variant = scenario.variants().stream()
                .filter(v -> v.variantId().equals("shuffled"))
                .findFirst()
                .orElseThrow();

        assertPasses(scenario, variant);
    }
}
