package com.specagent.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:E07ConflictTest.java
 *
 * 测试目标:E07——冲突(P2 语料)。变体 A(未解决):互斥诉求必须进入显式
 * 澄清流程(REQUEST_USER_INPUT),绝不静默做优先级取舍。变体 B(已解决,
 * E07-resolved):用户已做出权衡,agent 必须收敛,不得再次追问。
 * 各变体均包含同义改写(paraphrase)版本。
 */
class E07ConflictTest extends EvalHarnessBase {

    @Test
    void unresolvedConflictEntersExplicitResolution() {
        ScenarioDefinition scenario = EvalCorpus.e07();
        VariantSpec variant = scenario.variants().get(0);

        ObservationEnvelope observation = runScenario(scenario, variant);

        assertThat(observation.violations())
                .as("E07 unresolved violations: %s", observation.violations())
                .isEmpty();
        assertThat(observation.actualPrimaryAction()).isEqualTo("REQUEST_USER_INPUT");
    }

    @Test
    void unresolvedParaphraseVariantEntersExplicitResolution() {
        ScenarioDefinition scenario = EvalCorpus.e07();
        VariantSpec variant = scenario.variants().stream()
                .filter(v -> v.variantId().equals("unresolved-paraphrase"))
                .findFirst()
                .orElseThrow();

        assertPasses(scenario, variant);
    }

    @Test
    void resolvedConflictConvergesWithoutReasking() {
        ScenarioDefinition scenario = EvalCorpus.e07Resolved();
        VariantSpec variant = scenario.variants().get(0);

        ObservationEnvelope observation = runScenario(scenario, variant);

        assertThat(observation.violations())
                .as("E07 resolved violations: %s", observation.violations())
                .isEmpty();
        assertThat(observation.actualPrimaryAction()).isEqualTo("RESPOND_TO_USER");
    }

    @Test
    void resolvedParaphraseVariantConverges() {
        ScenarioDefinition scenario = EvalCorpus.e07Resolved();
        VariantSpec variant = scenario.variants().stream()
                .filter(v -> v.variantId().equals("resolved-paraphrase"))
                .findFirst()
                .orElseThrow();

        assertPasses(scenario, variant);
    }
}
