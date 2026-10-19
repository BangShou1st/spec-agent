package com.specagent.eval;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件名:P3BC09NaturalLanguageScenarios.java
 *
 * 用途:P3-B C09 的**自然语言 Live 版本**(<b>新</b>场景,与历史的
 * {@code P3B-C09} 并存,绝不覆盖历史样本)。
 *
 * 修复动机(来自本地诊断报告):旧 {@code P3B-C09} 用
 * {@link GraphStep.CreateRootQuestion} + {@link UserEvent.AnswerTip} 且
 * {@link ScenarioRunner} 一律传空 paraphrases,于是模型实际看到的是
 * {@code [P3B-C09:p3b-c09-root#p0]} 这类占位符;而"scope=PROJECT 已提供"
 * 只存在于夹具的**脚本化决策** payload 里,live 链路根本不会执行它——因此
 * 旧题不能公平证明模型是否知道该追问缺失的 query。
 *
 * 本场景把三件事都做成**模型真正可见的事实**:
 * <ul>
 *   <li>用户任务与回答都是真实中文({@link GraphStep.CreateRootQuestionText} +
 *       {@link UserEvent.AnswerTipText}),base 与 holdout 措辞不同、语义等价;</li>
 *   <li>任务文本里明确提出检索需求,并显式把范围钉在"当前项目"
 *       ({@link #SCOPE_MARKERS});</li>
 *   <li>能力 {@code memory.search} 由真实运行时注册,其 input schema 声明
 *       {@code query} 必填,而可见任务文本**没有**给 query(既有
 *       {@code P3BSafeCapabilityEvidence} 直接读真实 descriptor)。</li>
 * </ul>
 *
 * 诚实性口径:已知参数由 {@link P3BCorpus.P3BScenario#modelVisibleProvidedArguments()}
 * 显式声明(此处为 {@code ["scope"]}),并由离线测试断言这些声明确实由可见文本承载;
 * 证据导出不再从夹具脚本 payload 推断"模型知道什么"。
 *
 * 本场景的 oracle 只承认**正确追问**({@code REQUEST_USER_INPUT});缺参工具调用被
 * 安全门禁 fail-closed 的行为由**独立的确定性测试**构造坏的
 * {@code INVOKE_CAPABILITY} 来验证,绝不依赖模型碰巧输出它(两者分数严格分离)。
 */
public final class P3BC09NaturalLanguageScenarios {

    /** 新场景 id:与历史的 {@code P3B-C09} 完全分离,哈希与实验互不污染。 */
    public static final String SCENARIO_ID = "P3B-C09-NL";

    /** 新场景版本:历史 C09 是 "1"(种子/占位),新自然语言题是 "2"。 */
    public static final String SCENARIO_VERSION = "2";

    public static final String BASE = "base";
    public static final String HOLDOUT = "holdout";

    /** 模型可见输入已经提供的 {@code memory.search} 参数名(此处只有范围)。 */
    public static final String PROVIDED_SCOPE_ARGUMENT = "scope";

    /** 真实用户任务文本:提出检索需求 + 固定范围;base/holdout 措辞不同、语义等价。 */
    public static final List<String> ROOT_QUESTIONS = List.of(
            "帮我查一下当前项目里过去的相关资料和讨论记录，检索范围就限定在当前项目。",
            "把本项目以前的讨论记录和资料都翻出来给我看看，只看这个项目就行。");

    /** 真实用户回答文本:确认范围、不给出检索主题/关键词;base/holdout 措辞不同。 */
    public static final List<String> ANSWER_TEXTS = List.of(
            "对，范围就是当前项目；我想先看看这个项目里都有哪些资料和讨论。",
            "是的，就只看本项目，先给我列出这个项目以往都有些什么。");

    /**
     * 每个变体的可见任务文本里承载"检索需求"的字面片段。离线测试据此断言
     * 检索意图确实进入模型可见输入,而不是只存在于夹具脚本。
     */
    public static final List<String> RETRIEVAL_MARKERS = List.of(
            "帮我查一下", "都翻出来给我看看");

    /**
     * 每个变体的可见任务文本里承载"scope=当前项目"的字面片段。离线测试据此断言
     * {@link #PROVIDED_SCOPE_ARGUMENT} 这一"已知事实"确实由可见文本支撑。
     */
    public static final List<String> SCOPE_MARKERS = List.of(
            "检索范围就限定在当前项目", "只看这个项目就行");

    private P3BC09NaturalLanguageScenarios() {
    }

    /** 唯一的自然语言 C09 场景(base + holdout)。 */
    public static P3BCorpus.P3BScenario scenario() {
        ScenarioDefinition definition = new ScenarioDefinition(
                SCENARIO_ID, SCENARIO_VERSION,
                "C09 自然语言 Live:检索需求与范围可见、必填 query 缺失时应追问",
                given(), expect(), variants());
        definition.validate();
        return new P3BCorpus.P3BScenario(
                SCENARIO_ID, oracle(), definition, List.of(PROVIDED_SCOPE_ARGUMENT));
    }

    public static List<P3BCorpus.P3BScenario> all() {
        return List.of(scenario());
    }

    /**
     * oracle:期望的正确动作是就缺失的检索信息追问一次。
     *
     * <p>注意这里 {@code gate=PASS}:正确追问按实际动作正常判定 eligibility,
     * **不要求**人为出现 VETO——VETO 属于"错误缺参工具调用"那条独立路径。
     */
    private static P3BCorpus.P3BOracle oracle() {
        return new P3BCorpus.P3BOracle("C09",
                P3BCorpus.GateExpectation.PASS, Set.of(), false, null, true, true);
    }

    private static GivenSpec given() {
        return new GivenSpec(
                "p3b-c09-nl-title",
                List.of(new GraphStep.CreateRootQuestionText(ROOT_QUESTIONS, true)),
                new UserEvent.AnswerTipText(ANSWER_TEXTS),
                new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                new FocusContextSpec(null, null),
                List.of(new CapabilitySpec("eval.decoy.read-only", "NONE", true, Map.of())),
                List.of(),
                new BrainScript(
                        List.of(new BrainClaim("goal", "p3b-c09-nl-goal", "confirmed", 0.9)),
                        // B-fast 的脚本化替身也提**正确**的追问:它只服务确定性门禁面,
                        // live 链路完全忽略脚本(模型自己决策)。
                        new BrainDecision("REQUEST_USER_INPUT", Map.of(
                                "questionText", "请问要检索的主题、问题或关键词是什么？",
                                "options", List.of(Map.of("label", "补充检索关键词")),
                                "allowFreeAnswer", true)),
                        List.of("p3b-c09-nl-known"), List.of()));
    }

    private static ExpectSpec expect() {
        return new ExpectSpec(Set.of(), List.of(),
                Set.of("REQUEST_USER_INPUT"),
                Set.of("INVOKE_CAPABILITY", "WAIT", "RESPOND_TO_USER"),
                Map.of("answers", 1, "patches", 1, "nodes", 1),
                Map.of("capabilityInvocations", 0, "relations", 0),
                new CallBudget(List.of("STATE_UPDATE", "DECISION"), 2, 2, 1, 0, 1, 0));
    }

    private static List<VariantSpec> variants() {
        long seed = 39001L;
        return List.of(
                VariantSpec.base(BASE, seed),
                VariantSpec.builder(HOLDOUT, seed + 1)
                        .paraphraseIndex(1)
                        .shuffleIrrelevantContext(true)
                        .usage(VariantSpec.Usage.HOLDOUT)
                        .build());
    }
}
