package com.specagent.eval;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 文件名:ScenarioContractTest.java
 *
 * 测试目标:场景契约/schema(P2 评估工具链,TDD)。场景必须是数据驱动的
 * 声明,而非定制测试逻辑。契约包含身份、given(初始图 + 用户事件 +
 * 路由/focus 上下文 + 能力 + 资源 + 大脑脚本)和 expect(不变量、属性、
 * 动作、状态增量、调用预算)。变体在不把场景绑定到固定 UUID、固定句子、
 * 固定顺序或固定路由名的前提下参数化同一个场景。
 */
class ScenarioContractTest {

    private static ScenarioDefinition minimalScenario(String scenarioId, List<VariantSpec> variants) {
        return new ScenarioDefinition(
                scenarioId,
                "1",
                "smoke coverage",
                new GivenSpec(
                        "project-title-seed",
                        List.of(new GraphStep.CreateRootQuestion("question-seed", true)),
                        new UserEvent.AnswerTip("answer-seed"),
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(),
                        List.of(),
                        new BrainScript(
                                List.of(new BrainClaim("goal", "outcome clarified", "confirmed", 0.9)),
                                new BrainDecision("REQUEST_USER_INPUT", Map.of(
                                        "questionText", "What is the most important outcome?",
                                        "options", List.of(Map.of("label", "Clarify")),
                                        "allowFreeAnswer", true)),
                                List.of("known fact"),
                                List.of())),
                new ExpectSpec(
                        Set.of("GRAPH_INTEGRITY", "SHARED_STATE_IDENTITY"),
                        List.of(new PropertyCheck("ANSWER_PERSISTED", Map.of())),
                        Set.of("REQUEST_USER_INPUT"),
                        Set.of("INVOKE_CAPABILITY"),
                        Map.of("answers", 1, "patches", 1),
                        Map.of("capabilityInvocations", 1),
                        CallBudget.normalAnswerCycle()),
                variants);
    }

    @Test
    void scenarioHashIsStableForIdenticalInputs() {
        ScenarioDefinition first = minimalScenario("E01", List.of(VariantSpec.base("base", 42L)));
        ScenarioDefinition second = minimalScenario("E01", List.of(VariantSpec.base("base", 42L)));

        assertThat(first.scenarioHash()).isEqualTo(second.scenarioHash());
        assertThat(first.scenarioHash()).isNotBlank();
    }

    @Test
    void scenarioHashChangesWhenSemanticsChange() {
        ScenarioDefinition base = minimalScenario("E01", List.of(VariantSpec.base("base", 42L)));
        ScenarioDefinition changedAction = minimalScenario("E01", List.of(VariantSpec.base("base", 43L)));

        assertThat(base.scenarioHash()).isNotEqualTo(changedAction.scenarioHash());
    }

    @Test
    void scenarioHashIgnoresVariantOrderingButNotVariantContent() {
        VariantSpec a = VariantSpec.base("variant-a", 1L);
        VariantSpec b = VariantSpec.base("variant-b", 2L);
        ScenarioDefinition ordered = minimalScenario("E01", List.of(a, b));
        ScenarioDefinition reordered = minimalScenario("E01", List.of(b, a));

        assertThat(ordered.scenarioHash()).isEqualTo(reordered.scenarioHash());
    }

    @Test
    void variantIdsMustBeUniqueWithinScenario() {
        assertThatThrownBy(() -> minimalScenario("E01",
                        List.of(VariantSpec.base("dup", 1L), VariantSpec.base("dup", 2L)))
                        .validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("variant");
    }

    @Test
    void scenarioRequiresAtLeastOneVariant() {
        assertThatThrownBy(() -> minimalScenario("E01", List.of()).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("variant");
    }

    @Test
    void forbiddenAndAcceptableActionsMustNotOverlap() {
        ScenarioDefinition scenario = new ScenarioDefinition(
                "E01", "1", "overlap",
                minimalScenario("E01", List.of(VariantSpec.base("base", 1L))).given(),
                new ExpectSpec(
                        Set.of(),
                        List.of(),
                        Set.of("WAIT"),
                        Set.of("WAIT"),
                        Map.of(), Map.of(),
                        CallBudget.normalAnswerCycle()),
                List.of(VariantSpec.base("base", 1L)));

        assertThatThrownBy(scenario::validate)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("WAIT");
    }

    @Test
    void calibrationAndHoldoutVariantsAreTrackedSeparately() {
        VariantSpec calibration = VariantSpec.builder("cal-1", 11L).usage(VariantSpec.Usage.CALIBRATION).build();
        VariantSpec holdout = VariantSpec.builder("hold-1", 12L).usage(VariantSpec.Usage.HOLDOUT).build();
        ScenarioDefinition scenario =
                minimalScenario("E07", List.of(calibration, holdout, VariantSpec.base("base", 13L)));
        scenario.validate();

        assertThat(scenario.calibrationVariants()).containsExactly("cal-1");
        assertThat(scenario.holdoutVariants()).containsExactly("hold-1");
    }

    @Test
    void variantSupportsAntiOverfitAxesWithoutFixedSentences() {
        VariantSpec variant = VariantSpec.builder("paraphrase-1", 99L)
                .paraphraseIndex(2)
                .shuffleIrrelevantContext(true)
                .focusDiffersFromActive(true)
                .decoyCapability("decoy.read-only")
                .decoyResourceText("unrelated background note")
                .routeVariation("fork-label-variant")
                .usage(VariantSpec.Usage.CALIBRATION)
                .build();

        assertThat(variant.paraphraseIndex()).isEqualTo(2);
        assertThat(variant.shuffleIrrelevantContext()).isTrue();
        assertThat(variant.focusDiffersFromActive()).isTrue();
        assertThat(variant.decoyCapabilities()).containsExactly("decoy.read-only");
        assertThat(variant.usage()).isEqualTo(VariantSpec.Usage.CALIBRATION);
    }

    @Test
    void callBudgetNormalAnswerCycleExpectsTwoProductionCalls() {
        CallBudget budget = CallBudget.normalAnswerCycle();

        assertThat(budget.expectedStages()).containsExactly("STATE_UPDATE", "DECISION");
        assertThat(budget.maxProductionCalls()).isEqualTo(2);
        assertThat(budget.minProductionCalls()).isEqualTo(2);
    }
}
