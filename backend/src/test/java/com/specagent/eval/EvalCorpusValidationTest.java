package com.specagent.eval;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:EvalCorpusValidationTest.java
 *
 * 测试目标:语料校验(阻塞 CI):语料中的每个场景都能通过 validate、
 * 哈希稳定、场景 ID 唯一,并显式声明 calibration/holdout 用途。
 */
class EvalCorpusValidationTest {

    @Test
    void everyScenarioValidates() {
        for (ScenarioDefinition scenario : EvalCorpus.all()) {
            scenario.validate();
        }
    }

    @Test
    void scenarioIdsAreUnique() {
        Set<String> ids = new HashSet<>();
        for (ScenarioDefinition scenario : EvalCorpus.all()) {
            assertThat(ids.add(scenario.scenarioId()))
                    .as("duplicate scenario id: %s", scenario.scenarioId())
                    .isTrue();
        }
    }

    @Test
    void scenarioHashesAreStableAndUnique() {
        Set<String> hashes = new HashSet<>();
        for (ScenarioDefinition scenario : EvalCorpus.all()) {
            String first = scenario.scenarioHash();
            String second = scenario.scenarioHash();
            assertThat(first).isEqualTo(second);
            assertThat(first).isNotBlank();
            hashes.add(first);
        }
        assertThat(hashes).hasSize(EvalCorpus.all().size());
    }

    @Test
    void corpusCoversRequiredScenarios() {
        Set<String> ids = new HashSet<>();
        for (ScenarioDefinition scenario : EvalCorpus.all()) {
            ids.add(scenario.scenarioId());
        }
        assertThat(ids).contains("E01", "E06", "E07", "E07-resolved", "E17", "E25", "E25-stale");
    }

    @Test
    void everyScenarioDeclaresACallBudget() {
        for (ScenarioDefinition scenario : EvalCorpus.all()) {
            assertThat(scenario.expect().callBudget()).isNotNull();
            assertThat(scenario.expect().callBudget().maxProductionCalls())
                    .as("scenario %s budget", scenario.scenarioId())
                    .isLessThanOrEqualTo(2);
        }
    }
}
