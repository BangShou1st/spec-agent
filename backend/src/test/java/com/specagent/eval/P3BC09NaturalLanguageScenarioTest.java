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
 * 测试目标:证明自然语言 C09 的三道题在**语义上站得住脚**:
 * <ul>
 *   <li>{@code P3B-C09-NL}(历史自然语言题)一字未改,只作为历史证据保留;</li>
 *   <li>{@code P3B-C09-BROAD}(场景 A)是**宽泛但可执行**的检索目标:宽泛目标
 *       <b>不是</b>天然缺参,派生 query 的 {@code memory.search} 调用与追问
 *       同等合法;</li>
 *   <li>{@code P3B-C09-AMBIG}(场景 B)确实缺少**不可推导的具体检索对象**:
 *       项目范围已定、对象未给出、无任何隐藏线索能确定它,正确下一步是追问。</li>
 * </ul>
 *
 * 覆盖:无占位符、可见事实承载、真实 schema、base/holdout 语义等价、缺失对象
 * 不可推导、宽泛目标可派生的回归、正确追问与错误缺参工具调用的**独立**门禁
 * 判定,以及新旧场景的哈希冻结与隔离。
 */
class P3BC09NaturalLanguageScenarioTest extends EvalHarnessBase {

    /** 历史 C09 的场景哈希:锁定为常量,任何无意改动都会让本测试失败。 */
    private static final String FROZEN_HISTORICAL_C09_HASH = "dd7bff1a6c77fb51";

    /** 历史自然语言场景 P3B-C09-NL 的内容哈希:同样锁定。 */
    private static final String FROZEN_NATURAL_LANGUAGE_C09_HASH = "b9b2bcb0d0abb417";

    /** 场景 A(P3B-C09-BROAD)的内容哈希:独立于历史题,锁定为常量。 */
    private static final String FROZEN_BROAD_C09_HASH = "ae34865dd18c51ed";

    /** 场景 B(P3B-C09-AMBIG)的内容哈希:独立于历史题,锁定为常量。 */
    private static final String FROZEN_AMBIG_C09_HASH = "6e2484e0a7547e2c";

    private static P3BCorpus.P3BScenario historicalC09() {
        return P3BCorpus.all().stream()
                .filter(scenario -> "P3B-C09".equals(scenario.scenarioId()))
                .findFirst().orElseThrow();
    }

    private static P3BCorpus.P3BScenario naturalLanguage() {
        return P3BC09NaturalLanguageScenarios.scenario();
    }

    private static P3BCorpus.P3BScenario broad() {
        return P3BC09NaturalLanguageScenarios.broadScenario();
    }

    private static P3BCorpus.P3BScenario ambiguous() {
        return P3BC09NaturalLanguageScenarios.ambiguousScenario();
    }

    // -- 0. 哈希冻结与隔离 ---------------------------------------------------------

    @Test
    void allNaturalLanguageC09ScenariosHaveFrozenDistinctHashes() {
        List<P3BCorpus.P3BScenario> all = P3BC09NaturalLanguageScenarios.all();
        assertThat(all).hasSize(3);
        assertThat(all.stream().map(P3BCorpus.P3BScenario::scenarioId).toList())
                .containsExactlyInAnyOrder("P3B-C09-NL", "P3B-C09-BROAD", "P3B-C09-AMBIG");
        assertThat(all.stream().map(scenario -> scenario.definition().scenarioHash())
                .distinct().count())
                .as("每一道题必须有独立内容哈希,报告结果目录据此隔离")
                .isEqualTo(3);
        assertThat(naturalLanguage().definition().scenarioHash())
                .isEqualTo(FROZEN_NATURAL_LANGUAGE_C09_HASH);
        assertThat(broad().definition().scenarioHash()).isEqualTo(FROZEN_BROAD_C09_HASH);
        assertThat(ambiguous().definition().scenarioHash()).isEqualTo(FROZEN_AMBIG_C09_HASH);
        // 三题与历史 P3B-C09 的哈希两两不同:不同题永远不会被混比。
        assertThat(Set.of(
                FROZEN_HISTORICAL_C09_HASH,
                FROZEN_NATURAL_LANGUAGE_C09_HASH,
                FROZEN_BROAD_C09_HASH,
                FROZEN_AMBIG_C09_HASH)).hasSize(4);
        assertThat(historicalC09().definition().scenarioHash())
                .isEqualTo(FROZEN_HISTORICAL_C09_HASH);
    }

    @Test
    void historicalC09CorpusRemainsUntouchedByTheNewScenarios() {
        // 新增的三道自然语言题绝不能进入历史 18x2 语料,否则冻结基线会被悄悄改写。
        assertThat(P3BCorpus.all()).hasSize(18);
        assertThat(P3BCorpus.all().stream().map(P3BCorpus.P3BScenario::scenarioId))
                .doesNotContain("P3B-C09-NL", "P3B-C09-BROAD", "P3B-C09-AMBIG");
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

    @Test
    void broadAndAmbiguousVisibleTextContainNoSeedPlaceholders() {
        for (P3BCorpus.P3BScenario scenario : List.of(broad(), ambiguous())) {
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

    // -- 2. 历史自然语言题的可见文本承载检索需求与 scope ---------------------------

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

        for (P3BCorpus.P3BScenario scenario : List.of(naturalLanguage(), broad(), ambiguous())) {
            Map<String, Object> facts =
                    P3BSafeCapabilityEvidence.summarize(descriptors, scenario);

            assertThat(facts.get("memory_search_visible"))
                    .as("%s: memory.search must be visible to the model", scenario.scenarioId())
                    .isEqualTo(true);
            assertThat(facts.get("required_arguments_verified")).isEqualTo(true);
            assertThat(facts.get("memory_search_query_required")).isEqualTo(true);
            assertThat(facts.get("memory_search_provided_argument_names")).isEqualTo(List.of("scope"));
            assertThat(facts.get("memory_search_missing_arguments")).isEqualTo(List.of("query"));
            Map<?, ?> capability = (Map<?, ?>) ((List<?>) facts.get("available_capabilities")).get(0);
            assertThat(((List<?>) capability.get("required_arguments")).stream()
                    .map(String::valueOf).toList()).contains("query");
        }
    }

    // -- 4. base/holdout 语义等价、措辞不同 ---------------------------------------

    @Test
    void baseAndHoldoutParaphraseDifferButStaySemanticallyEquivalent() {
        ScenarioDefinition definition = naturalLanguage().definition();
        assertThat(definition.variants()).hasSize(2);
        assertThat(definition.variants().get(0).usage()).isEqualTo(VariantSpec.Usage.GENERAL);
        assertThat(definition.variants().get(1).usage()).isEqualTo(VariantSpec.Usage.HOLDOUT);
        assertThat(definition.holdoutVariants()).containsExactly("holdout");
        assertThat(definition.variants().get(1).paraphraseIndex()).isEqualTo(1);

        List<String> roots = P3BC09NaturalLanguageScenarios.ROOT_QUESTIONS;
        List<String> answers = P3BC09NaturalLanguageScenarios.ANSWER_TEXTS;
        assertThat(roots.get(0)).isNotEqualTo(roots.get(1));
        assertThat(answers.get(0)).isNotEqualTo(answers.get(1));

        for (int index = 0; index < 2; index++) {
            assertThat(roots.get(index)).contains(P3BC09NaturalLanguageScenarios.RETRIEVAL_MARKERS.get(index));
            assertThat(roots.get(index)).contains(P3BC09NaturalLanguageScenarios.SCOPE_MARKERS.get(index));
        }
    }

    @Test
    void broadBaseAndHoldoutAreSemanticallyEquivalentButDifferInWording() {
        assertParaphrasesDifferButStayEquivalent(
                broad(),
                P3BC09NaturalLanguageScenarios.BROAD_ROOT_QUESTIONS,
                P3BC09NaturalLanguageScenarios.BROAD_ANSWER_TEXTS,
                P3BC09NaturalLanguageScenarios.BROAD_RETRIEVAL_MARKERS,
                P3BC09NaturalLanguageScenarios.BROAD_SCOPE_MARKERS);
    }

    @Test
    void ambiguousBaseAndHoldoutAreSemanticallyEquivalentButDifferInWording() {
        assertParaphrasesDifferButStayEquivalent(
                ambiguous(),
                P3BC09NaturalLanguageScenarios.AMBIG_ROOT_QUESTIONS,
                P3BC09NaturalLanguageScenarios.AMBIG_ANSWER_TEXTS,
                P3BC09NaturalLanguageScenarios.AMBIG_TARGET_MARKERS,
                P3BC09NaturalLanguageScenarios.AMBIG_SCOPE_MARKERS);
        // 每个变体的回答都显式承认"具体对象尚未说明":该事实由可见输入承载。
        for (int index = 0; index < 2; index++) {
            assertThat(P3BC09NaturalLanguageScenarios.AMBIG_ANSWER_TEXTS.get(index))
                    .contains(P3BC09NaturalLanguageScenarios.AMBIG_UNRESOLVED_OBJECT_MARKERS.get(index));
        }
        // 两个变体同时承载"不是浏览所有资料"这一约束。
        for (int index = 0; index < 2; index++) {
            assertThat(P3BC09NaturalLanguageScenarios.AMBIG_ROOT_QUESTIONS.get(index))
                    .contains(P3BC09NaturalLanguageScenarios.AMBIG_NARROW_MARKERS.get(index));
        }
    }

    private static void assertParaphrasesDifferButStayEquivalent(
            P3BCorpus.P3BScenario scenario, List<String> roots, List<String> answers,
            List<String> needMarkers, List<String> scopeMarkers) {
        ScenarioDefinition definition = scenario.definition();
        assertThat(definition.variants()).hasSize(2);
        assertThat(definition.holdoutVariants()).containsExactly("holdout");
        assertThat(definition.variants().get(1).paraphraseIndex()).isEqualTo(1);
        assertThat(roots.get(0)).isNotEqualTo(roots.get(1));
        assertThat(answers.get(0)).isNotEqualTo(answers.get(1));
        for (int index = 0; index < 2; index++) {
            assertThat(roots.get(index)).contains(needMarkers.get(index));
            assertThat(roots.get(index)).contains(scopeMarkers.get(index));
        }
        assertThat(scenario.modelVisibleProvidedArguments())
                .containsExactly(P3BC09NaturalLanguageScenarios.PROVIDED_SCOPE_ARGUMENT);
    }

    // -- 5. 场景 A:宽泛检索目标不是天然缺参 ---------------------------------------

    @Test
    void broadScenarioAcceptsBothDerivedQueryCallAndClarification() {
        ExpectSpec expect = broad().definition().expect();
        assertThat(expect.acceptablePrimaryActions())
                .as("宽泛检索目标下,自主检索与追问都合法:不得硬性规定唯一正解")
                .containsExactlyInAnyOrder("INVOKE_CAPABILITY", "REQUEST_USER_INPUT");
        assertThat(expect.forbiddenActions())
                .as("只有'既不检索也不追问'才是错的")
                .containsExactlyInAnyOrder("WAIT", "RESPOND_TO_USER");
        assertThat(broad().oracle().gate()).isEqualTo(P3BCorpus.GateExpectation.PASS);
        assertThat(broad().oracle().vetoReasonCodes()).isEmpty();
    }

    @Test
    void broadRetrievalGoalIsNotInherentlyMissingRequiredQuery() {
        // 回归:同一道宽泛检索题,派生 query 的 memory.search 调用必须被放行并真正执行,
        // 绝不能被判为"缺少必填参数"。
        ObservationEnvelope observation =
                runScenario(broad().definition(), broad().definition().variants().get(0));

        assertThat(observation.violations())
                .as("broad scenario violations: %s", observation.violations())
                .isEmpty();
        assertThat(observation.actualPrimaryAction()).isEqualTo("INVOKE_CAPABILITY");
        assertThat(observation.executionResult()).isEqualTo("completed");

        Map<String, Object> eligibility = observation.semanticTrace().stages()
                .getOrDefault("ACTION_ELIGIBILITY", Map.of());
        assertThat(P3BDecisionQuality.gateOutcome(eligibility))
                .isEqualTo(P3BDecisionQuality.GateOutcome.PASSED);
        assertThat(P3BDecisionQuality.gateReasonCodes(eligibility))
                .as("可派生的宽泛检索绝不能被判为 MISSING_REQUIRED_CAPABILITY_ARGUMENT")
                .doesNotContain("MISSING_REQUIRED_CAPABILITY_ARGUMENT");
        assertThat(persistedCapabilityInvocations())
                .as("派生的检索调用必须真正落库执行,而不是被门禁否决")
                .isEqualTo(1);
    }

    @Test
    void broadDerivedQueryIsGroundedInTheUserGoalNotFabricated() {
        List<String> roots = P3BC09NaturalLanguageScenarios.BROAD_ROOT_QUESTIONS;
        for (int index = 0; index < roots.size(); index++) {
            for (String source : P3BC09NaturalLanguageScenarios.BROAD_DERIVED_QUERY_SOURCES) {
                assertThat(roots.get(index) + P3BC09NaturalLanguageScenarios.BROAD_ANSWER_TEXTS.get(index))
                        .as("派生检索词的组成 '%s' 必须逐字来自用户可见目标", source)
                        .contains(source);
            }
            assertThat(roots.get(index))
                    .contains(P3BC09NaturalLanguageScenarios.BROAD_BREADTH_MARKERS.get(index));
        }
    }

    // -- 6. 场景 B:特定对象未知,且无隐藏线索 --------------------------------------

    @Test
    void ambiguousVisibleTextCarriesTargetScopeAndUnknownObject() {
        List<String> roots = P3BC09NaturalLanguageScenarios.AMBIG_ROOT_QUESTIONS;
        List<String> answers = P3BC09NaturalLanguageScenarios.AMBIG_ANSWER_TEXTS;
        assertThat(roots).hasSize(2);
        assertThat(answers).hasSize(2);
        for (int index = 0; index < 2; index++) {
            assertThat(roots.get(index))
                    .contains(P3BC09NaturalLanguageScenarios.AMBIG_TARGET_MARKERS.get(index));
            assertThat(roots.get(index))
                    .contains(P3BC09NaturalLanguageScenarios.AMBIG_NARROW_MARKERS.get(index));
            assertThat(roots.get(index))
                    .contains(P3BC09NaturalLanguageScenarios.AMBIG_SCOPE_MARKERS.get(index));
        }
    }

    @Test
    void ambiguousScenarioExposesNoHiddenClueThatIdentifiesTheObject() {
        P3BCorpus.P3BScenario scenario = ambiguous();
        GivenSpec given = scenario.definition().given();

        // 图里只有一个真实自然语言的根问题:没有附加资源、没有别的节点可以"预置"检索对象。
        assertThat(given.initialGraph()).hasSize(1);
        assertThat(given.initialGraph().get(0)).isInstanceOf(GraphStep.CreateRootQuestionText.class);
        assertThat(given.resources()).isEmpty();

        // 唯一声明的能力是只读诱饵,不是 memory.search:检索对象不可能来自夹具能力。
        assertThat(given.capabilities().stream().map(CapabilitySpec::capabilityId).toList())
                .containsExactly("eval.decoy.read-only");

        // 脚本化决策不是能力调用,其文案也不点名任何具体对象。
        BrainDecision decision = given.brainScript().decision();
        assertThat(decision.actionFamily()).isEqualTo("REQUEST_USER_INPUT");
        assertThat(String.valueOf(decision.payload())).doesNotContain("memory.search");
        assertThat(given.brainScript().claims())
                .allSatisfy(claim -> assertThat(claim.textSeed())
                        .doesNotContain("memory.search"));

        // 可见输入 + 夹具文案里都不得出现任何"具体检索对象"的候选词。
        for (String canary : P3BC09NaturalLanguageScenarios.AMBIG_LEAK_CANARY_TOKENS) {
            assertThat(String.join("|",
                    P3BC09NaturalLanguageScenarios.AMBIG_ROOT_QUESTIONS)
                    + "|" + String.join("|", P3BC09NaturalLanguageScenarios.AMBIG_ANSWER_TEXTS)
                    + "|" + decision.payload())
                    .as("'%s' 一旦出现就说明具体对象已被泄漏,本题不能再标注为缺少必要信息", canary)
                    .doesNotContain(canary);
        }
    }

    @Test
    void ambiguousTaskDiffersSemanticallyFromTheBroadTask() {
        // 场景 A 的宽泛目标可以被安全地转换成检索词……
        assertThat(P3BC09NaturalLanguageScenarios.BROAD_DERIVED_QUERY_SOURCES)
                .allSatisfy(source -> assertThat(
                        String.join("|", P3BC09NaturalLanguageScenarios.BROAD_ROOT_QUESTIONS))
                        .contains(source));
        // ……而场景 B 的可见输入里**没有任何**可区分的具体检索对象。
        assertThat(ambiguous().definition().expect().acceptablePrimaryActions())
                .containsExactly("REQUEST_USER_INPUT");
        assertThat(ambiguous().definition().expect().forbiddenActions())
                .contains("INVOKE_CAPABILITY");
        for (P3BCorpus.P3BScenario scenario : List.of(broad(), ambiguous())) {
            assertThat(scenario.definition().scenarioHash())
                    .isNotEqualTo(naturalLanguage().definition().scenarioHash());
        }
    }

    // -- 7. 场景 B:正确追问合法(门禁 PASS),不要求人为 VETO ----------------------

    @Test
    void ambiguousCorrectClarificationIsEligibleWithoutAnyVeto() {
        P3BCorpus.P3BScenario scenario = ambiguous();
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
        // 而且用场景 B 的"对象未知"题面,证明门禁对该形态**独立地**给出 fail-closed
        // 原因码。默认 shadow 档位只记录裁决,因此这里只断言裁决与原因码;
        // "被否决的调用绝不执行"由 enforced 档位的独立测试
        // {@code P3BC09AmbiguousGateEnforcementTest} 给出行为证据。
        ScenarioDefinition bad = badMissingQueryInvocation();
        ObservationEnvelope observation = runScenario(bad, bad.variants().get(0));

        Map<String, Object> eligibility = observation.semanticTrace().stages()
                .getOrDefault("ACTION_ELIGIBILITY", Map.of());
        assertThat(P3BDecisionQuality.gateOutcome(eligibility))
                .isEqualTo(P3BDecisionQuality.GateOutcome.VETOED);
        assertThat(P3BDecisionQuality.gateReasonCodes(eligibility))
                .contains("MISSING_REQUIRED_CAPABILITY_ARGUMENT");
    }

    /** 该 run 真实持久化的能力调用行数(含成功与失败;真实能力也会被算进来)。 */
    private int persistedCapabilityInvocations() {
        java.util.UUID projectId = scenarioRunner.lastProjectId();
        if (projectId == null) {
            return 0;
        }
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM capability_invocations WHERE project_id = ?",
                Integer.class, projectId);
        return count == null ? 0 : count;
    }

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
        assertThat(definition.given().brainScript().decision().actionFamily())
                .isEqualTo("INVOKE_CAPABILITY");

        assertThat(definition.scenarioHash())
                .as("historical C09 scenario hash is frozen; a mismatch means the old task was tampered with")
                .isEqualTo(FROZEN_HISTORICAL_C09_HASH);
    }

    @Test
    void historicalNaturalLanguageScenarioAndOracleRemainFrozen() {
        P3BCorpus.P3BScenario historical = naturalLanguage();
        assertThat(historical.scenarioId()).isEqualTo("P3B-C09-NL");
        assertThat(historical.definition().scenarioVersion()).isEqualTo("2");
        assertThat(historical.definition().expect().acceptablePrimaryActions())
                .containsExactly("REQUEST_USER_INPUT");
        assertThat(historical.oracle().gate()).isEqualTo(P3BCorpus.GateExpectation.PASS);
        assertThat(historical.definition().scenarioHash())
                .as("P3B-C09-NL is retained read-only as historical evidence")
                .isEqualTo(FROZEN_NATURAL_LANGUAGE_C09_HASH);
    }

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

    private static ScenarioDefinition badMissingQueryInvocation() {
        GivenSpec given = new GivenSpec(
                "p3b-c09-ambig-bad-title",
                List.of(new GraphStep.CreateRootQuestionText(
                        P3BC09NaturalLanguageScenarios.AMBIG_ROOT_QUESTIONS, true)),
                new UserEvent.AnswerTipText(P3BC09NaturalLanguageScenarios.AMBIG_ANSWER_TEXTS),
                new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                new FocusContextSpec(null, null),
                List.of(new CapabilitySpec("eval.decoy.read-only", "NONE", true, Map.of())),
                List.of(),
                new BrainScript(
                        List.of(new BrainClaim("open_question",
                                "p3b-c09-ambig-bad-unresolved-target", "unresolved", 0.9)),
                        new BrainDecision("INVOKE_CAPABILITY", Map.of(
                                "capabilityId", "memory.search",
                                "arguments", Map.of("scope", "PROJECT"))),
                        List.of("p3b-c09-ambig-bad-known"), List.of()));
        ScenarioDefinition definition = new ScenarioDefinition(
                "P3B-C09-AMBIG-BAD", "1", "缺必填 query 的工具调用必须 fail-closed",
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
