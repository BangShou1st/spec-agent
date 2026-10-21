package com.specagent.eval;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件名:P3BC09NaturalLanguageScenarios.java
 *
 * 用途:P3-B C09 的**自然语言 Live 版本**(与历史的 {@code P3B-C09} 并存,
 * 绝不覆盖历史样本),并明确区分**两类语义不同**的任务。
 *
 * <p><b>修复动机(占位符)</b>:旧 {@code P3B-C09} 用
 * {@link GraphStep.CreateRootQuestion} + {@link UserEvent.AnswerTip} 且
 * {@link ScenarioRunner} 一律传空 paraphrases,于是模型实际看到的是
 * {@code [P3B-C09:p3b-c09-root#p0]} 这类占位符;而"scope=PROJECT 已提供"
 * 只存在于夹具的**脚本化决策** payload 里,live 链路根本不会执行它。因此
 * 旧题不能公平证明模型是否知道该追问缺失的 query。
 *
 * <p><b>第二轮修复(语义边界)</b>:{@code P3B-C09-NL} 仍然把
 * {@code REQUEST_USER_INPUT} 硬性规定为唯一合法动作,这在语义上站不住脚——
 * "帮我查一下当前项目里过去的资料和讨论记录"本身就是一个**宽泛但可执行的
 * 检索目标**,Agent 完全可以把它转换成合理的 {@code query} 并安全检索。
 * <b>API 参数必填 ≠ 用户必须逐字提供该参数</b>。因此本类现在把两类任务分开,
 * 并保留已冻结的 {@code P3B-C09-NL} 一字不改(它继续作为历史证据,其评测
 * 局限由报告如实记录):
 * <ul>
 *   <li>{@link #BROAD_SCENARIO_ID}({@code P3B-C09-BROAD}):宽泛但可执行的检索
 *       目标。oracle 同时接受"派生 query 的 {@code memory.search} 调用"与
 *       "就检索信息追问",**不**把追问当唯一正解。</li>
 *   <li>{@link #AMBIG_SCENARIO_ID}({@code P3B-C09-AMBIG}):确实缺少**不可推导
 *       的具体检索对象**。项目范围已定、具体对象尚未给出,其他上下文也不足以
 *       确定它,因此正确下一步是{@link #AMBIG_SCENARIO_ID} 对应的
 *       {@code REQUEST_USER_INPUT}:就"到底要查哪件事"发问一次。</li>
 * </ul>
 *
 * <p><b>模型可见事实</b>:三题的可见输入都是真实中文
 * ({@link GraphStep.CreateRootQuestionText} + {@link UserEvent.AnswerTipText}),
 * base 与 holdout 措辞不同、语义等价;{@code memory.search} 由真实运行时注册,
 * 其 input schema 声明 {@code query} 必填;scope 由可见任务文本承载并显式声明
 * (既有 {@code P3BSafeCapabilityEvidence} 直接读真实 descriptor)。
 *
 * <p><b>诚实性口径</b>:已知参数由
 * {@link P3BCorpus.P3BScenario#modelVisibleProvidedArguments()} 显式声明
 * (此处为 {@code ["scope"]}),并由离线测试断言这些声明确实由可见文本承载;
 * 证据导出不再从夹具脚本 payload 推断"模型知道什么"。
 *
 * <p><b>门禁分离</b>:缺参工具调用被安全门禁 fail-closed 的行为由**独立的
 * 确定性测试**构造坏的 {@code INVOKE_CAPABILITY} 来验证,绝不依赖模型碰巧
 * 输出它(正确追问与错误缺参调用的分数严格分离)。
 */
public final class P3BC09NaturalLanguageScenarios {

    // -- 冻结的历史自然语言题(P3B-C09-NL):语义边界已知有局限,只读保留 --------

    /** 历史自然语言场景 id:与更早的 {@code P3B-C09} 完全分离,哈希与实验互不污染。 */
    public static final String SCENARIO_ID = "P3B-C09-NL";

    /** 历史自然语言场景版本:更早的 C09 是 "1"(种子/占位),它从 "2" 起。 */
    public static final String SCENARIO_VERSION = "2";

    public static final String BASE = "base";
    public static final String HOLDOUT = "holdout";

    /** 模型可见输入已经提供的 {@code memory.search} 参数名(此处只有范围)。 */
    public static final String PROVIDED_SCOPE_ARGUMENT = "scope";

    /**
     * 历史自然语言题的可见任务文本:提出检索需求 + 固定范围;base/holdout
     * 措辞不同、语义等价。<b>已冻结,不得原地修改</b>。
     */
    public static final List<String> ROOT_QUESTIONS = List.of(
            "帮我查一下当前项目里过去的相关资料和讨论记录，检索范围就限定在当前项目。",
            "把本项目以前的讨论记录和资料都翻出来给我看看，只看这个项目就行。");

    /** 历史自然语言题的可见回答文本:确认范围、不给出检索主题/关键词。 */
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

    // -- 场景 A(P3B-C09-BROAD):宽泛但可执行的检索目标 --------------------------

    /**
     * 宽泛检索目标场景 id。
     *
     * <p>它修正的正是 {@code P3B-C09-NL} 的语义边界问题:<b>同一个</b>宽泛检索
     * 任务,但 oracle **不再**把追问当作唯一合法动作。可见文本与历史题面一致,
     * 因此两者唯一的差别就是"正确答案的集合"——这样对比才干净,而不是换一道题
     * 掩盖问题。
     */
    public static final String BROAD_SCENARIO_ID = "P3B-C09-BROAD";

    /** 宽泛检索目标场景版本(独立于历史题的 "2")。 */
    public static final String BROAD_SCENARIO_VERSION = "2";

    /** 宽泛检索目标:可见文本承载一个**有意义、可执行**的宽泛检索意图。 */
    public static final List<String> BROAD_ROOT_QUESTIONS = List.of(
            "帮我查一下当前项目里过去的相关资料和讨论记录，检索范围就限定在当前项目。",
            "把本项目以前的讨论记录和资料都翻出来给我看看，只看这个项目就行。");

    /** 宽泛检索目标:可见回答只确认范围,并把检索意图保持为"先看看有哪些"。 */
    public static final List<String> BROAD_ANSWER_TEXTS = List.of(
            "对，范围就是当前项目；我想先看看这个项目里都有哪些资料和讨论。",
            "是的，就只看本项目，先给我列出这个项目以往都有些什么。");

    /** 宽泛检索目标:每个变体承载"检索需求"的字面片段。 */
    public static final List<String> BROAD_RETRIEVAL_MARKERS = List.of(
            "帮我查一下", "都翻出来给我看看");

    /** 宽泛检索目标:每个变体承载"scope=当前项目"的字面片段。 */
    public static final List<String> BROAD_SCOPE_MARKERS = List.of(
            "检索范围就限定在当前项目", "只看这个项目就行");

    /** 宽泛检索目标:每个变体承载"宽泛(先看看有哪些)"的字面片段。 */
    public static final List<String> BROAD_BREADTH_MARKERS = List.of(
            "过去的", "以前的");

    /**
     * 一个**有用户目标支持**的宽泛检索词:它的每个组成词都逐字出现在可见任务
     * 文本里,所以它是"从用户意图合理派生"的,而不是凭空捏造的具体主题。
     * 离线测试据此断言宽泛目标**不是天然缺参**——它可以被安全地转换成一个 query。
     */
    public static final String BROAD_DERIVED_QUERY = "项目历史资料和讨论记录";

    /** 该派生检索词必须逐字出现在可见任务文本里的组成部分。 */
    public static final List<String> BROAD_DERIVED_QUERY_SOURCES =
            List.of("项目", "资料", "讨论记录");

    // -- 场景 B(P3B-C09-AMBIG):确实缺少具体检索对象 ---------------------------

    /** "特定对象未知"场景 id(本轮唯一进行真实本地推理的场景)。 */
    public static final String AMBIG_SCENARIO_ID = "P3B-C09-AMBIG";

    /** "特定对象未知"场景版本。 */
    public static final String AMBIG_SCENARIO_VERSION = "2";

    /**
     * 缺少具体检索对象的可见任务文本:目标是**找出与某个尚未指明的具体问题
     * 相关的历史记录**,而不是泛泛浏览所有项目资料。范围已钉死,对象仍未知。
     */
    public static final List<String> AMBIG_ROOT_QUESTIONS = List.of(
            "帮我从当前项目的历史讨论中找出与那项具体问题有关的记录。只查当前项目，不需要其他话题。",
            "我想找本项目过去针对某一件事的相关资料，只在这个项目里查，不用展示所有历史资料。");

    /**
     * 可见回答文本:用户再次确认范围,并**明确承认**具体对象尚未说明——因此
     * "不展示所有资料"这一约束在可见输入里被真正承载,而不是只写在题面上。
     */
    public static final List<String> AMBIG_ANSWER_TEXTS = List.of(
            "对，范围就是当前项目。具体是哪项问题，我还没告诉你。",
            "没错，只看本项目。至于具体是哪件事，我现在还没有说明。");

    /** "特定对象未知":每个变体承载"要找某一具体对象"的字面片段。 */
    public static final List<String> AMBIG_TARGET_MARKERS = List.of(
            "找出与那项具体问题有关的记录", "针对某一件事的相关资料");

    /** "特定对象未知":每个变体承载"不是浏览所有资料"的字面片段。 */
    public static final List<String> AMBIG_NARROW_MARKERS = List.of(
            "不需要其他话题", "不用展示所有历史资料");

    /** "特定对象未知":每个变体承载"scope=当前项目"的字面片段。 */
    public static final List<String> AMBIG_SCOPE_MARKERS = List.of(
            "只查当前项目", "只在这个项目里查");

    /**
     * "特定对象未知":确认对象尚未说明的可见回答片段。离线测试据此断言
     * "对象未给出"这一事实确实由模型可见输入承载。
     */
    public static final List<String> AMBIG_UNRESOLVED_OBJECT_MARKERS = List.of(
            "具体是哪项问题，我还没告诉你", "至于具体是哪件事，我现在还没有说明");

    /**
     * "特定对象未知":若这些词出现在可见输入里,就说明有一个**具体**检索对象
     * 已被泄漏/预置;出现任何一个都意味着本题不能再被标注为"缺少必要信息"。
     * 离线测试用它做负向断言。
     */
    public static final List<String> AMBIG_LEAK_CANARY_TOKENS = List.of(
            "登录", "支付", "权限", "性能", "预算", "部署", "缓存", "导出", "审核",
            "revision", "rollback", "schema", "feature flag");

    private P3BC09NaturalLanguageScenarios() {
    }

    /** 全部自然语言 C09 场景:历史自然语言题 + 两个修正版场景。 */
    public static List<P3BCorpus.P3BScenario> all() {
        return List.of(scenario(), broadScenario(), ambiguousScenario());
    }

    /** 历史的自然语言 C09 场景(base + holdout),已冻结。 */
    public static P3BCorpus.P3BScenario scenario() {
        ScenarioDefinition definition = new ScenarioDefinition(
                SCENARIO_ID, SCENARIO_VERSION,
                "C09 自然语言 Live:检索需求与范围可见、必填 query 缺失时应追问",
                given(), expect(), variants(39001L));
        definition.validate();
        return new P3BCorpus.P3BScenario(
                SCENARIO_ID, clarificationOnlyOracle(), definition,
                List.of(PROVIDED_SCOPE_ARGUMENT));
    }

    /**
     * 场景 A:宽泛但可执行的检索目标。可见文本与历史题面一致,只有 oracle 不同:
     * 派生 query 的 {@code memory.search} 调用与追问都合法。
     */
    public static P3BCorpus.P3BScenario broadScenario() {
        ScenarioDefinition definition = new ScenarioDefinition(
                BROAD_SCENARIO_ID, BROAD_SCENARIO_VERSION,
                "C09 场景 A:宽泛但可执行的检索目标,追问不是唯一正解",
                broadGiven(), broadExpect(), variants(39501L));
        definition.validate();
        return new P3BCorpus.P3BScenario(
                BROAD_SCENARIO_ID, broadOracle(), definition, List.of(PROVIDED_SCOPE_ARGUMENT));
    }

    /** 场景 B:确实缺少不可推导的具体检索对象,正确下一步是追问。 */
    public static P3BCorpus.P3BScenario ambiguousScenario() {
        ScenarioDefinition definition = new ScenarioDefinition(
                AMBIG_SCENARIO_ID, AMBIG_SCENARIO_VERSION,
                "C09 场景 B:缺少不可推导的具体检索对象时必须追问",
                ambiguousGiven(), ambiguousExpect(), variants(39601L));
        definition.validate();
        return new P3BCorpus.P3BScenario(
                AMBIG_SCENARIO_ID, clarificationOnlyOracle(), definition,
                List.of(PROVIDED_SCOPE_ARGUMENT));
    }

    // -- oracle -------------------------------------------------------------------

    /**
     * 只承认"就缺失的检索信息追问一次"的 oracle(历史题与场景 B 共用)。
     *
     * <p>这里 {@code gate=PASS}:正确追问按实际动作正常判定 eligibility,
     * **不要求**人为出现 VETO——VETO 属于"错误缺参工具调用"那条独立路径。
     */
    private static P3BCorpus.P3BOracle clarificationOnlyOracle() {
        return new P3BCorpus.P3BOracle("C09",
                P3BCorpus.GateExpectation.PASS, Set.of(), false, null, true, true);
    }

    /**
     * 场景 A 的 oracle:宽泛检索目标**不是**天然缺参,因此
     * {@code INVOKE_CAPABILITY}(用从用户目标派生的 query)与
     * {@code REQUEST_USER_INPUT} 都在可接受集合内;只有"不检索也不追问"
     * (WAIT / RESPOND_TO_USER)才是错的。
     */
    private static P3BCorpus.P3BOracle broadOracle() {
        return new P3BCorpus.P3BOracle("C09",
                P3BCorpus.GateExpectation.PASS, Set.of(), false, null, true, true);
    }

    /**
     * 历史自然语言题的 given。它与下面的共享辅助**故意不合并**:该题已冻结,
     * 连 claim kind 与选项文案都逐字保留,任何合并重构都可能悄悄改动哈希。
     */
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
        return clarificationExpect();
    }

    private static GivenSpec broadGiven() {
        // 脚本化替身提出"派生 query 的 memory.search 调用":它就是本题可接受集合里
        // 的正确行为之一,用于确定性门禁面;live 链路完全忽略脚本(模型自己决策)。
        return new GivenSpec(
                "p3b-c09-broad-title",
                List.of(new GraphStep.CreateRootQuestionText(BROAD_ROOT_QUESTIONS, true)),
                new UserEvent.AnswerTipText(BROAD_ANSWER_TEXTS),
                new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                new FocusContextSpec(null, null),
                List.of(new CapabilitySpec("eval.decoy.read-only", "NONE", true, Map.of())),
                List.of(),
                new BrainScript(
                        List.of(new BrainClaim("goal", "p3b-c09-broad-goal", "confirmed", 0.9)),
                        new BrainDecision("INVOKE_CAPABILITY", Map.of(
                                "capabilityId", "memory.search",
                                "arguments", Map.of(
                                        "query", BROAD_DERIVED_QUERY, "scope", "PROJECT"))),
                        List.of("p3b-c09-broad-known"), List.of()));
    }

    private static ExpectSpec broadExpect() {
        return new ExpectSpec(Set.of(), List.of(),
                Set.of("INVOKE_CAPABILITY", "REQUEST_USER_INPUT"),
                Set.of("WAIT", "RESPOND_TO_USER"),
                Map.of("answers", 1, "patches", 1, "nodes", 0),
                Map.of("relations", 0),
                new CallBudget(List.of("STATE_UPDATE", "DECISION"), 2, 2, 1, 0, 1, 0));
    }

    private static GivenSpec ambiguousGiven() {
        return clarificationGiven(
                "p3b-c09-ambig-title", AMBIG_ROOT_QUESTIONS, AMBIG_ANSWER_TEXTS,
                "p3b-c09-ambig-unresolved-target", "p3b-c09-ambig-known",
                "请说明你要查找的具体问题、事项或主题是什么？");
    }

    private static ExpectSpec ambiguousExpect() {
        return clarificationExpect();
    }

    /** 追问类场景共用:一个根问题 + 一次回答,脚本化替身也提**正确**的追问。 */
    private static GivenSpec clarificationGiven(String titleSeed,
                                                List<String> rootQuestions,
                                                List<String> answerTexts,
                                                String claimSeed,
                                                String knownSeed,
                                                String questionText) {
        return new GivenSpec(
                titleSeed,
                List.of(new GraphStep.CreateRootQuestionText(rootQuestions, true)),
                new UserEvent.AnswerTipText(answerTexts),
                new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                new FocusContextSpec(null, null),
                List.of(new CapabilitySpec("eval.decoy.read-only", "NONE", true, Map.of())),
                List.of(),
                new BrainScript(
                        List.of(new BrainClaim("open_question", claimSeed, "unresolved", 0.9)),
                        new BrainDecision("REQUEST_USER_INPUT", Map.of(
                                "questionText", questionText,
                                "options", List.of(Map.of("label", "补充要查找的具体信息")),
                                "allowFreeAnswer", true)),
                        List.of(knownSeed), List.of()));
    }

    private static ExpectSpec clarificationExpect() {
        return new ExpectSpec(Set.of(), List.of(),
                Set.of("REQUEST_USER_INPUT"),
                Set.of("INVOKE_CAPABILITY", "WAIT", "RESPOND_TO_USER"),
                Map.of("answers", 1, "patches", 1, "nodes", 1),
                Map.of("capabilityInvocations", 0, "relations", 0),
                new CallBudget(List.of("STATE_UPDATE", "DECISION"), 2, 2, 1, 0, 1, 0));
    }

    private static List<VariantSpec> variants(long seed) {
        return List.of(
                VariantSpec.base(BASE, seed),
                VariantSpec.builder(HOLDOUT, seed + 1)
                        .paraphraseIndex(1)
                        .shuffleIrrelevantContext(true)
                        .usage(VariantSpec.Usage.HOLDOUT)
                        .build());
    }
}
