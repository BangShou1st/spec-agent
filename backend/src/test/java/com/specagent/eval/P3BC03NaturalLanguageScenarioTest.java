package com.specagent.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名: P3BC03NaturalLanguageScenarioTest.java
 *
 * 测试目标: 证明自然语言 C03 的三道题在**语义边界与状态流转**上站得住脚:
 * <ul>
 *   <li>{@code P3B-C03-NL}: 针对已回答并形成 confirmed claim 的事实，改写后的重复追问属于禁止动作；</li>
 *   <li>{@code P3B-C03-NEW-REQ}: 原问题已解决，但缺少真正新的必要信息时，允许发起追问；</li>
 *   <li>{@code P3B-C03-AMBIG}: 用户回答含糊未决(claim 为 unresolved)时，允许针对原问题澄清追问；</li>
 *   <li>历史 18×2 语料哈希完全冻结，绝不污染历史基线；</li>
 *   <li>真实自然语言可见文本中无任何占位符。</li>
 * </ul>
 */
class P3BC03NaturalLanguageScenarioTest extends EvalHarnessBase {

    /** 历史 C03 的场景哈希: 锁定为常量，任何无意改动都会让本测试失败。 */
    private static final String FROZEN_HISTORICAL_C03_HASH = "4b70d1eea1c16480";

    private static P3BCorpus.P3BScenario historicalC03() {
        return P3BCorpus.all().stream()
                .filter(scenario -> "P3B-C03".equals(scenario.scenarioId()))
                .findFirst().orElseThrow();
    }

    private static P3BCorpus.P3BScenario rewordedRepeat() {
        return P3BC03NaturalLanguageScenarios.rewordedRepeatScenario();
    }

    private static P3BCorpus.P3BScenario newNecessary() {
        return P3BC03NaturalLanguageScenarios.newNecessaryScenario();
    }

    private static P3BCorpus.P3BScenario ambiguous() {
        return P3BC03NaturalLanguageScenarios.ambiguousScenario();
    }

    // -- 0. 哈希冻结与隔离 ---------------------------------------------------------

    @Test
    void historicalC03CorpusRemainsUntouchedByTheNewScenarios() {
        // 新增的三道自然语言题绝不能进入历史 18x2 语料，否则冻结基线会被悄悄改写。
        assertThat(P3BCorpus.all()).hasSize(18);
        assertThat(P3BCorpus.all().stream().map(P3BCorpus.P3BScenario::scenarioId))
                .doesNotContain("P3B-C03-NL", "P3B-C03-NEW-REQ", "P3B-C03-AMBIG");
        assertThat(historicalC03().definition().scenarioHash())
                .isEqualTo(FROZEN_HISTORICAL_C03_HASH);
    }

    @Test
    void allNaturalLanguageC03ScenariosHaveDistinctHashes() {
        List<P3BCorpus.P3BScenario> all = P3BC03NaturalLanguageScenarios.all();
        assertThat(all).hasSize(3);
        assertThat(all.stream().map(P3BCorpus.P3BScenario::scenarioId).toList())
                .containsExactlyInAnyOrder("P3B-C03-NL", "P3B-C03-NEW-REQ", "P3B-C03-AMBIG");

        Set<String> hashes = Set.of(
                FROZEN_HISTORICAL_C03_HASH,
                rewordedRepeat().definition().scenarioHash(),
                newNecessary().definition().scenarioHash(),
                ambiguous().definition().scenarioHash());
        assertThat(hashes)
                .as("历史 C03 与三道新自然语言题的哈希必须两两不同，实验目录完全隔离")
                .hasSize(4);
    }

    // -- 1. 渲染后没有占位符 -------------------------------------------------------

    @Test
    void renderedVisibleTaskTextHasNoSeedPlaceholders() {
        for (P3BCorpus.P3BScenario scenario : P3BC03NaturalLanguageScenarios.all()) {
            ScenarioDefinition definition = scenario.definition();
            GraphStep.CreateRootQuestionText root =
                    (GraphStep.CreateRootQuestionText) definition.given().initialGraph().get(0);
            UserEvent.AnswerTipText answer =
                    (UserEvent.AnswerTipText) definition.given().userEvent();
            for (VariantSpec variant : definition.variants()) {
                assertThat(root.questionText(variant.paraphraseIndex()))
                        .as("visible root text of %s must not be a seed placeholder",
                                scenario.scenarioId())
                        .doesNotContain("[P3B", "#p0", "#p1");
                assertThat(answer.freeText(variant.paraphraseIndex()))
                        .as("visible answer text of %s must not be a seed placeholder",
                                scenario.scenarioId())
                        .doesNotContain("[P3B", "#p0", "#p1");
            }
        }
    }

    // -- 2. 行为边界与 ActionEligibility 验证 --------------------------------------

    @Test
    void rewordedRepeatScenarioDisallowsRequestUserInputInOracle() {
        P3BCorpus.P3BScenario scenario = rewordedRepeat();
        ScenarioDefinition def = scenario.definition();
        // 验证可接受动作与禁止动作集合
        assertThat(def.expect().acceptablePrimaryActions())
                .contains("RESPOND_TO_USER", "CREATE_NODE")
                .doesNotContain("REQUEST_USER_INPUT");
        assertThat(def.expect().forbiddenActions())
                .contains("REQUEST_USER_INPUT", "INVOKE_CAPABILITY");
        assertThat(scenario.oracle().gate())
                .isEqualTo(P3BCorpus.GateExpectation.NO_DETERMINISTIC_SURFACE);
    }

    @Test
    void newNecessaryQuestionIsAcceptableWhenInformationIsMissing() {
        P3BCorpus.P3BScenario scenario = newNecessary();
        ScenarioDefinition def = scenario.definition();
        // 缺新必要信息时，追问是合法动作
        assertThat(def.expect().acceptablePrimaryActions())
                .contains("REQUEST_USER_INPUT");
        assertThat(def.expect().forbiddenActions())
                .doesNotContain("REQUEST_USER_INPUT");
    }

    @Test
    void ambiguousAnswerAllowsClarificationRequest() {
        P3BCorpus.P3BScenario scenario = ambiguous();
        ScenarioDefinition def = scenario.definition();
        // 用户回答含糊未决时，追问是合法动作
        assertThat(def.expect().acceptablePrimaryActions())
                .contains("REQUEST_USER_INPUT");
        assertThat(def.expect().forbiddenActions())
                .doesNotContain("REQUEST_USER_INPUT");
    }

    @Test
    void offlineBfastExecutionSucceedsForNaturalLanguageC03() {
        // 在离线 B-fast 链路下执行一遍场景，断言无崩溃且正常到达终态
        ObservationEnvelope obs = runScenario(rewordedRepeat().definition(),
                VariantSpec.base("base", 33501L));
        assertThat(obs.executionResult()).isEqualTo("completed");
        assertThat(obs.actualPrimaryAction()).isEqualTo("RESPOND_TO_USER");
        assertThat(obs.violations()).isEmpty();
    }
}
