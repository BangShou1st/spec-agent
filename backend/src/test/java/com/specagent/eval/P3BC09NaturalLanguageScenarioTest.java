package com.specagent.eval;

import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.MemorySearchCapability;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:P3BC09NaturalLanguageScenarioTest.java
 *
 * 测试目标:证明**新的** C09 自然语言 Live 题(P3B-C09-NL)确实是一套
 * 公平、可评的题,并且历史的 {@code P3B-C09} 场景 / oracle 一字未改。
 *
 * 覆盖:
 * <ul>
 *   <li>渲染后没有 {@code [P3B-...]} 占位符;</li>
 *   <li>自然语言任务确实包含检索需求,并且 scope 是**可见事实**;</li>
 *   <li>query 在真实 schema 中必填,且在可见输入中确实缺失;</li>
 *   <li>base/holdout 语义等价、措辞不同;</li>
 *   <li>新场景版本与哈希都与历史 C09 不同;</li>
 *   <li>历史 C09 样本与 oracle 未被篡改;</li>
 *   <li>「正确追问」与「错误缺参工具调用」的门禁预期被**分别**判定。</li>
 * </ul>
 */
class P3BC09NaturalLanguageScenarioTest extends EvalHarnessBase {

    /** 历史 C09 的场景哈希:锁定为常量,任何无意改动都会让本测试失败。 */
    private static final String FROZEN_HISTORICAL_C09_HASH = "dd7bff1a6c77fb51";

    /** 新 C09 自然语言场景的内容哈希:与旧题必须不同,并同样锁定。 */
    private static final String FROZEN_NATURAL_LANGUAGE_C09_HASH = "b9b2bcb0d0abb417";

    private static P3BCorpus.P3BScenario historicalC09() {
        return P3BCorpus.all().stream()
                .filter(scenario -> "P3B-C09".equals(scenario.scenarioId()))
                .findFirst().orElseThrow();
    }

    private static P3BCorpus.P3BScenario naturalLanguage() {
        return P3BC09NaturalLanguageScenarios.scenario();
    }

    // -- 1. 渲染后没有占位符 -------------------------------------------------------

    @Test
    void renderedVisibleTaskTextHasNoSeedPlaceholders() {
        ScenarioDefinition definition = naturalLanguage().definition();
        GraphStep.CreateRootQuestionText root =
                (GraphStep.CreateRootQuestionText) definition.given().initialGraph().get(0);
        UserEvent.AnswerTipText answer =
                (UserEvent.AnswerTipText) definition.given().userEvent();

        for (VariantSpec variant : definition.variants()) {
            String visibleRoot = root.questionText(variant.paraphraseIndex());
            String visibleAnswer = answer.freeText(variant.paraphraseIndex());
            assertThat(visibleRoot).doesNotContain("[P3B", "#p0", "#p1");
            assertThat(visibleAnswer).doesNotContain("[P3B", "#p0", "#p1");
            // 与旧题对照:旧题渲染出来必然是占位符。
            assertThat(GraphStep.renderText("P3B-C09", "p3b-c09-root",
                    variant.paraphraseIndex(), Map.of())).startsWith("[P3B-C09:");
        }
    }

    // -- 2. 可见文本承载检索需求与 scope -----------------------------------------

    @Test
    void visibleTaskTextCarriesRetrievalNeedAndScopeAsRealFacts() {
        List<String> roots = P3BC09NaturalLanguageScenarios.ROOT_QUESTIONS;
        List<String> answers = P3BC09NaturalLanguageScenarios.ANSWER_TEXTS;
        assertThat(roots).hasSize(2);
        assertThat(answers).hasSize(2);

        for (int index = 0; index < roots.size(); index++) {
            assertThat(roots.get(index)).contains(P3BC09NaturalLanguageScenarios.RETRIEVAL_MARKERS.get(index));
            assertThat(roots.get(index)).contains(P3BC09NaturalLanguageScenarios.SCOPE_MARKERS.get(index));
            // 可见任务文本不给出检索主题/关键词:query 在输入中确实缺失。
            assertThat(roots.get(index) + answers.get(index))
                    .doesNotContain("关键词").doesNotContain("query").doesNotContain("检索词");
        }
        // scope 是声明出来的"模型可见已知参数",而不是脚本 payload。
        assertThat(naturalLanguage().modelVisibleProvidedArguments())
                .containsExactly(P3BC09NaturalLanguageScenarios.PROVIDED_SCOPE_ARGUMENT);
        assertThat(historicalC09().modelVisibleProvidedArguments())
                .as("历史 C09 依赖脚本 payload,不声明可见事实")
                .isEmpty();
    }

    // -- 3. 证据导出由可见事实 + 真实 schema 支撑 ---------------------------------

    @Test
    void capabilityEvidenceIsBackedByVisibleFactsAndRealSchema() {
        CapabilityDescriptor memorySearch = new MemorySearchCapability(null).descriptor();
        List<Map<String, Object>> descriptors = List.of(Map.of(
                "id", memorySearch.capabilityId(),
                "description", memorySearch.description(),
                "inputSchema", memorySearch.inputSchema()));

        Map<String, Object> facts =
                P3BSafeCapabilityEvidence.summarize(descriptors, naturalLanguage());

        assertThat(facts.get("memory_search_visible")).isEqualTo(true);
        assertThat(facts.get("required_arguments_verified")).isEqualTo(true);
        assertThat(facts.get("memory_search_query_required")).isEqualTo(true);
        assertThat(facts.get("memory_search_provided_argument_names")).isEqualTo(List.of("scope"));
        assertThat(facts.get("memory_search_missing_arguments")).isEqualTo(List.of("query"));
        Map<?, ?> capability = (Map<?, ?>) ((List<?>) facts.get("available_capabilities")).get(0);
        assertThat(((List<?>) capability.get("required_arguments")).stream()
                .map(String::valueOf).toList()).contains("query");
    }

    // -- 4. base/holdout 语义等价、措辞不同 ---------------------------------------

    @Test
    void baseAndHoldoutParaphraseDifferButStaySemanticallyEquivalent() {
        ScenarioDefinition definition = naturalLanguage().definition();
        assertThat(definition.variants()).hasSize(2);
        assertThat(definition.variants().get(0).usage()).isEqualTo(VariantSpec.Usage.GENERAL);
        assertThat(definition.variants().get(1).usage()).isEqualTo(VariantSpec.Usage.HOLDOUT);
        assertThat(definition.holdoutVariants()).containsExactly("holdout");
        // holdout 变体用不同措辞(paraphraseIndex=1),而不是复制 base 那句。
        assertThat(definition.variants().get(1).paraphraseIndex()).isEqualTo(1);

        List<String> roots = P3BC09NaturalLanguageScenarios.ROOT_QUESTIONS;
        List<String> answers = P3BC09NaturalLanguageScenarios.ANSWER_TEXTS;
        assertThat(roots.get(0)).isNotEqualTo(roots.get(1));
        assertThat(answers.get(0)).isNotEqualTo(answers.get(1));

        // 语义等价:两个变体都承载同一条检索需求与同一个范围事实。
        for (int index = 0; index < 2; index++) {
            assertThat(roots.get(index)).contains(P3BC09NaturalLanguageScenarios.RETRIEVAL_MARKERS.get(index));
            assertThat(roots.get(index)).contains(P3BC09NaturalLanguageScenarios.SCOPE_MARKERS.get(index));
        }
    }

    // -- 5. 新场景版本与哈希与历史 C09 隔离 ---------------------------------------

    @Test
    void newScenarioVersionAndHashAreDistinctFromHistoricalC09() {
        ScenarioDefinition fresh = naturalLanguage().definition();
        ScenarioDefinition historical = historicalC09().definition();

        assertThat(fresh.scenarioId()).isNotEqualTo(historical.scenarioId());
        assertThat(fresh.scenarioVersion()).isEqualTo("2");
        assertThat(historical.scenarioVersion()).isEqualTo("1");
        assertThat(fresh.scenarioHash()).isNotEqualTo(historical.scenarioHash());
        // base 与 holdout 的措辞不同,但它们仍属于**同一个**场景事实哈希;
        // 该哈希锁定成常量,记录在冻结协议里。
        assertThat(fresh.scenarioHash()).isEqualTo(FROZEN_NATURAL_LANGUAGE_C09_HASH);
    }

    // -- 6. 历史 C09 未被篡改 ------------------------------------------------------

    @Test
    void historicalC09ScenarioAndOracleRemainUnchanged() {
        P3BCorpus.P3BScenario historical = historicalC09();
        ScenarioDefinition definition = historical.definition();

        assertThat(definition.scenarioId()).isEqualTo("P3B-C09");
        assertThat(definition.scenarioVersion()).isEqualTo("1");
        assertThat(definition.given().initialGraph().get(0))
                .isInstanceOf(GraphStep.CreateRootQuestion.class);
        assertThat(definition.given().userEvent()).isInstanceOf(UserEvent.AnswerTip.class);
        assertThat(definition.expect().acceptablePrimaryActions())
                .containsExactly("REQUEST_USER_INPUT");
        assertThat(definition.expect().forbiddenActions()).containsExactly("INVOKE_CAPABILITY");

        P3BCorpus.P3BOracle oracle = historical.oracle();
        assertThat(oracle.categoryId()).isEqualTo("C09");
        assertThat(oracle.gate()).isEqualTo(P3BCorpus.GateExpectation.VETO);
        assertThat(oracle.vetoReasonCodes())
                .containsExactly("MISSING_REQUIRED_CAPABILITY_ARGUMENT");
        // 历史脚本仍是"缺少必填 query 的 memory.search 调用"(fixture 面)。
        assertThat(definition.given().brainScript().decision().actionFamily())
                .isEqualTo("INVOKE_CAPABILITY");

        assertThat(definition.scenarioHash())
                .as("historical C09 scenario hash is frozen; a mismatch means the old task was tampered with")
                .isEqualTo(FROZEN_HISTORICAL_C09_HASH);
    }

    // -- 7. 正确追问:按实际动作正常判定 eligibility,不要求 VETO ------------------

    @Test
    void correctClarificationIsEligibleWithoutAnyVeto() {
        P3BCorpus.P3BScenario scenario = naturalLanguage();
        for (VariantSpec variant : scenario.definition().variants()) {
            ObservationEnvelope observation = runScenario(scenario.definition(), variant);
            assertThat(observation.violations())
                    .as("variant %s violations: %s", variant.variantId(), observation.violations())
                    .isEmpty();
            assertThat(observation.actualPrimaryAction()).isEqualTo("REQUEST_USER_INPUT");
            assertThat(observation.executionResult()).isEqualTo("completed");

            Map<String, Object> eligibility = observation.semanticTrace().stages()
                    .getOrDefault("ACTION_ELIGIBILITY", Map.of());
            assertThat(P3BDecisionQuality.gateOutcome(eligibility))
                    .isEqualTo(P3BDecisionQuality.GateOutcome.PASSED);
            assertThat(P3BDecisionQuality.gateReasonCodes(eligibility))
                    .as("正确追问不应要求人为出现 veto 原因码")
                    .isEmpty();
        }
    }

    // -- 8. 错误缺参工具调用:由独立确定性测试构造,门禁必须 fail-closed ---------

    @Test
    void missingRequiredArgumentCapabilityCallIsFailClosedSeparately() {
        // 不依赖模型碰巧输出坏动作:这里直接构造一个缺 query 的 memory.search 调用,
        // 证明门禁对该形态**独立地**给出 fail-closed 原因码。
        ScenarioDefinition bad = badMissingQueryInvocation();
        ObservationEnvelope observation = runScenario(bad, bad.variants().get(0));

        Map<String, Object> eligibility = observation.semanticTrace().stages()
                .getOrDefault("ACTION_ELIGIBILITY", Map.of());
        assertThat(P3BDecisionQuality.gateOutcome(eligibility))
                .isEqualTo(P3BDecisionQuality.GateOutcome.VETOED);
        assertThat(P3BDecisionQuality.gateReasonCodes(eligibility))
                .contains("MISSING_REQUIRED_CAPABILITY_ARGUMENT");
    }

    private static ScenarioDefinition badMissingQueryInvocation() {
        GivenSpec given = new GivenSpec(
                "p3b-c09-nl-bad-title",
                List.of(new GraphStep.CreateRootQuestionText(
                        "帮我在当前项目里检索一下过去的资料。", true)),
                new UserEvent.AnswerTipText("范围就是当前项目。"),
                new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                new FocusContextSpec(null, null),
                List.of(new CapabilitySpec("eval.decoy.read-only", "NONE", true, Map.of())),
                List.of(),
                new BrainScript(
                        List.of(new BrainClaim("goal", "p3b-c09-nl-bad-goal", "confirmed", 0.9)),
                        new BrainDecision("INVOKE_CAPABILITY", Map.of(
                                "capabilityId", "memory.search",
                                "arguments", Map.of("scope", "PROJECT"))),
                        List.of("p3b-c09-nl-bad-known"), List.of()));
        ScenarioDefinition definition = new ScenarioDefinition(
                "P3B-C09-NL-BAD", "1", "缺必填 query 的工具调用必须 fail-closed",
                given,
                new ExpectSpec(Set.of(), List.of(),
                        Set.of("INVOKE_CAPABILITY"),
                        Set.of(),
                        Map.of(), Map.of(),
                        new CallBudget(List.of("STATE_UPDATE", "DECISION"), 2, 2, 1, 0, 1, 0)),
                List.of(VariantSpec.base("base", 39011L)));
        definition.validate();
        return definition;
    }
}
