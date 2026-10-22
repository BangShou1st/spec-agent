package com.specagent.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件名:P3BCorpus.java
 *
 * 用途:P3-B 决策质量语料:项目 Agent 的三类核心判断(何时追问 / 何时调用工具 /
 * 何时结束或回应)在**真实 Java 运行时**上的可复跑基线。
 *
 * 与 P3-A 的关系:复用既有评测框架({@link ScenarioDefinition} /
 * {@link ExpectSpec} / {@link BrainScript} / {@link ScenarioRunner}),
 * **不新建平行框架**。{@link ScriptedBrain} 只在"结构化大脑输出"这一边界
 * 替身:图、快照、eligibility、policy、executor、续跑裁决全部仍是生产实现,因此
 * 这里测的是 Runtime 的**门禁质量**,而不是脚本本身。
 *
 * 诚实性口径(必须与 P3-A 一致):
 * <ul>
 *   <li>脚本化提案刻意复现**已记录的真实失败形态**(例如"相关被当成必须"的
 *       {@code INVOKE_CAPABILITY});它是 deterministic/scripted 基线,**不是**
 *       真实模型成绩。真实模型成绩由独立 live suite 记录。</li>
 *   <li>{@link #oracleFor(String)} 里 {@code deterministicSurface} 明确标注
 *       该类别在 Runtime 门禁层是否可被判分;语义类判断(prompt 级)不假装可判。</li>
 *   <li>{@code acceptableActions} 刻意收窄(不再像 P3-A 那样对大多数场景同时
 *       接受 RUI 与 RESPOND_TO_USER),否则无法区分三种故障模式。</li>
 * </ul>
 *
 * 标签来源:AI 编写的候选标签,{@code PENDING_HUMAN_REVIEW};可执行镜像见
 * {@code tools/p3b/p3b_scenarios.json}(同 id、同 oracle,含人工审核状态字段)。
 */
public final class P3BCorpus {

    /** Runtime 门禁对该场景提案的期望裁决。 */
    public enum GateExpectation {
        /** 期望 eligibility 校验器放行(不 veto)。 */
        PASS,
        /** 期望 eligibility 校验器否决,且原因码匹配 {@link P3BOracle#vetoReasonCodes}。 */
        VETO,
        /** 该类别在 Runtime 门禁层没有确定性判据(语义判断留给模型)。 */
        NO_DETERMINISTIC_SURFACE
    }

    /**
     * 单个 P3-B 场景的决策质量 oracle。
     *
     * @param categoryId            任务书里 16 个必备类别之一(C01..C18 覆盖)
     * @param gate                  Runtime eligibility 门禁的期望裁决
     * @param vetoReasonCodes       期望的 veto 原因码(仅 {@code VETO} 时校验)
     * @param knownEligibilityGap   已知且被接受的确定性缺口(如 WAIT 无表示)
     * @param continuationVerdict   期望的续跑裁决名;{@code null} 表示不判分
     * @param capabilityNecessityScored 该场景是否参与"工具调用必要性"判分
     * @param payloadValidityScored 该场景是否参与 payload 有效性判分
     */
    public record P3BOracle(String categoryId,
                            GateExpectation gate,
                            Set<String> vetoReasonCodes,
                            boolean knownEligibilityGap,
                            String continuationVerdict,
                            boolean capabilityNecessityScored,
                            boolean payloadValidityScored) {

        public P3BOracle {
            vetoReasonCodes = vetoReasonCodes == null ? Set.of() : Set.copyOf(vetoReasonCodes);
        }
    }

    /**
     * 一个 P3-B 场景:可执行定义 + 决策质量 oracle。
     *
     * @param modelVisibleProvidedArguments 该场景中**模型可见输入**已经提供的
     *     能力参数名(例如自然语言任务文本已经固定了 {@code scope=PROJECT})。
     *     证据导出必须以此为依据,而不是从夹具的脚本化决策 payload 推断——
     *     脚本参数在 live 链路里根本不会执行,不能当作"模型已经知道"。
     *     旧场景不声明该字段时保持原行为(空列表)。
     */
    public record P3BScenario(String scenarioId, P3BOracle oracle, ScenarioDefinition definition,
                              List<String> modelVisibleProvidedArguments) {

        public P3BScenario {
            modelVisibleProvidedArguments = modelVisibleProvidedArguments == null
                    ? List.of() : List.copyOf(modelVisibleProvidedArguments);
        }

        /** 旧的三参构造:不声明模型可见已知参数(沿用历史行为)。 */
        public P3BScenario(String scenarioId, P3BOracle oracle, ScenarioDefinition definition) {
            this(scenarioId, oracle, definition, List.of());
        }
    }

    private static final Set<String> NO_INVARIANTS = Set.of();

    private P3BCorpus() {
    }

    public static List<P3BScenario> all() {
        return List.of(
                c01MissingUserDecision(),
                c02VerbatimRepeatClarification(),
                c03RewordedRepeatClarification(),
                c04RequirementChange(),
                c05RequirementAlreadyClear(),
                c06CapabilityRelevantButNotNeeded(),
                c07CapabilityRelevantWithGroundedArguments(),
                c08CapabilityNeededWithArguments(),
                c09CapabilityNeededButMissingRequiredArgument(),
                c10ConditionAndException(),
                c11ConflictingConstraints(),
                c12ResolvedHistoricalConflict(),
                c13SupersededSource(),
                c14DuplicateDurableNode(),
                c15UnsupportedAssertion(),
                c16GenuineExternalWait(),
                c17AfterSuccessfulCapability(),
                c18ApprovalRequiredAction());
    }

    public static P3BOracle oracleFor(String scenarioId) {
        return all().stream()
                .filter(scenario -> scenario.scenarioId().equals(scenarioId))
                .findFirst()
                .map(P3BScenario::oracle)
                .orElseThrow(() -> new IllegalArgumentException("Unknown P3-B scenario: " + scenarioId));
    }

    // -- C01:缺少关键用户决策,必须追问 ------------------------------------------

    /**
     * 已明确的约束(员工可预约、大型会议室需审批)必须被保存;但"大型会议室
     * 如何界定"只能由用户决定,且阻碍下一步。正确行为是就这一项发问一次,
     * 而不是重问已答的权限/审批规则,也不是调能力。
     */
    private static P3BScenario c01MissingUserDecision() {
        return scenario("P3B-C01", "C01", "缺失的会议室口径只能由用户决定",
                simpleGiven("p3b-c01-root", List.of(
                        new BrainClaim("constraint", "p3b-c01-employee-can-book", "confirmed", 0.9),
                        new BrainClaim("constraint", "p3b-c01-large-needs-approval", "confirmed", 0.9),
                        new BrainClaim("open_question", "p3b-c01-large-room-threshold", "unresolved", 0.9)),
                        requestUserInput("p3b-c01-threshold-question"), List.of("eval.decoy.read-only")),
                expect(Set.of("REQUEST_USER_INPUT"),
                        Set.of("INVOKE_CAPABILITY", "WAIT", "RESPOND_TO_USER"),
                        Map.of("answers", 1, "patches", 1, "nodes", 1),
                        Map.of("capabilityInvocations", 0, "relations", 0),
                        budget(1)),
                variants(3101L),
                new P3BOracle("C01", GateExpectation.PASS, Set.of(), false,
                        "PARKED_USER_INPUT", false, true));
    }

    // -- C02:信息已给齐,不得重复追问(逐字重复,门禁可判) ----------------------

    /**
     * 用户已回答过该问题,提案又原样问一遍。这是 Runtime 能确定性判定的
     * "重复追问":{@code RESOLVED_BLOCKER}。
     */
    private static P3BScenario c02VerbatimRepeatClarification() {
        String seed = "p3b-c02-answered-question";
        return scenario("P3B-C02", "C02", "逐字重复已答问题必须被否决",
                simpleGiven(seed, List.of(
                        new BrainClaim("goal", "p3b-c02-goal", "confirmed", 0.9)),
                        requestUserInput(seed), List.of("eval.decoy.read-only")),
                expect(Set.of("RESPOND_TO_USER", "CREATE_NODE"),
                        Set.of("REQUEST_USER_INPUT", "INVOKE_CAPABILITY"),
                        Map.of("answers", 1, "patches", 1),
                        Map.of("nodes", 0, "capabilityInvocations", 0),
                        budget(0)),
                variants(3201L),
                new P3BOracle("C02", GateExpectation.VETO, Set.of("RESOLVED_BLOCKER"), false,
                        null, false, true));
    }

    // -- C03:信息已给齐,改写后的重复追问(门禁无处可判) -----------------------

    /**
     * 同一件事换一种措辞再问。Runtime 只有精确归一化比对,因此**判不了**;
     * 是否构成重复追问属于语义判断(prompt 规则 21)。本场景如实记录这一缺口,
     * 不把语义问题伪装成门禁问题。
     */
    private static P3BScenario c03RewordedRepeatClarification() {
        return scenario("P3B-C03", "C03", "改写后的重复追问:门禁无确定性判据",
                simpleGiven("p3b-c03-answered-question", List.of(
                        new BrainClaim("goal", "p3b-c03-goal", "confirmed", 0.9)),
                        requestUserInput("p3b-c03-reworded-question"),
                        List.of("eval.decoy.read-only")),
                expect(Set.of("RESPOND_TO_USER", "CREATE_NODE"),
                        Set.of("REQUEST_USER_INPUT", "INVOKE_CAPABILITY"),
                        Map.of("answers", 1, "patches", 1),
                        Map.of("capabilityInvocations", 0),
                        budget(0)),
                variants(3301L),
                new P3BOracle("C03", GateExpectation.NO_DETERMINISTIC_SURFACE, Set.of(), false,
                        null, false, true));
    }

    // -- C04:用户直接修改需求 -----------------------------------------------------

    /**
     * 用户改写目标(旧口径被明确撤销)。正确行为是直接回应并让图上的
     * 事实收敛,不追问、不调能力。
     */
    private static P3BScenario c04RequirementChange() {
        return scenario("P3B-C04", "C04", "需求被直接修改后直接回应",
                simpleGiven("p3b-c04-root", List.of(
                        new BrainClaim("goal", "p3b-c04-new-target", "confirmed", 0.9),
                        new BrainClaim("goal", "p3b-c04-superseded-target", "rejected", 0.9)),
                        respondToUser("p3b-c04-ack"), List.of("eval.decoy.read-only")),
                expect(Set.of("RESPOND_TO_USER"),
                        Set.of("REQUEST_USER_INPUT", "INVOKE_CAPABILITY"),
                        Map.of("answers", 1, "patches", 1, "nodes", 0),
                        Map.of("capabilityInvocations", 0, "relations", 0),
                        budget(0)),
                variants(3401L),
                new P3BOracle("C04", GateExpectation.PASS, Set.of(), false,
                        "TERMINAL_RESPONSE", true, true));
    }

    // -- C05:当前需求已经明确,可以直接回应 -------------------------------------

    private static P3BScenario c05RequirementAlreadyClear() {
        return scenario("P3B-C05", "C05", "需求已明确时直接回应而非继续追问",
                simpleGiven("p3b-c05-root", List.of(
                        new BrainClaim("goal", "p3b-c05-goal", "confirmed", 0.9),
                        new BrainClaim("success_criterion", "p3b-c05-metric", "confirmed", 0.9)),
                        respondToUser("p3b-c05-ack"), List.of("eval.decoy.read-only")),
                expect(Set.of("RESPOND_TO_USER"),
                        Set.of("REQUEST_USER_INPUT", "INVOKE_CAPABILITY", "CREATE_NODE"),
                        Map.of("answers", 1, "patches", 1, "nodes", 0),
                        Map.of("capabilityInvocations", 0, "relations", 0),
                        budget(0)),
                variants(3501L),
                new P3BOracle("C05", GateExpectation.PASS, Set.of(), false,
                        "TERMINAL_RESPONSE", true, true));
    }

    // -- C06:有相关 capability,但不需要调用(缺必填参数) -----------------------

    /**
     * 只确认了一条业务目标,没有任何"现在必须执行外部步骤"的证据。
     * 脚本化提案复现 P3-A 记录的真实形态:因为 {@code availableCapabilities}
     * 非空就调 {@code memory.search},而且**没有给出它声明的必填参数 query**。
     *
     * 这是 Runtime 能确定性判定的部分:被调用能力的**声明必填参数**必须在
     * 提案参数里真实存在。本轮的最小修复对象。
     */
    private static P3BScenario c06CapabilityRelevantButNotNeeded() {
        return scenario("P3B-C06", "C06", "相关能力不需要调用且缺少必填参数",
                simpleGiven("p3b-c06-root", List.of(
                        new BrainClaim("goal", "p3b-c06-goal", "confirmed", 0.9)),
                        invokeMemorySearch(Map.of("nodeRef", "step:tip")),
                        List.of("eval.decoy.read-only")),
                expect(Set.of("RESPOND_TO_USER", "REQUEST_USER_INPUT"),
                        Set.of("INVOKE_CAPABILITY"),
                        Map.of("answers", 1, "patches", 1, "nodes", 0),
                        Map.of("capabilityInvocations", 0, "relations", 0),
                        budget(1)),
                variants(3601L),
                new P3BOracle("C06", GateExpectation.VETO,
                        Set.of("MISSING_REQUIRED_CAPABILITY_ARGUMENT"), false,
                        null, true, true));
    }

    // -- C07:有相关 capability,但不需要调用(参数齐全) ------------------------

    /**
     * 与 C06 同因不同层:这里参数齐全,所以 Runtime 层**没有**任何确定性判据
     * 能阻止它——"相关 ≠ 必须"是语义判断,必须在模型侧解决(既有 R4 报告已
     * 证明结构事实不足以表达它)。本场景如实记为语义缺口,不计入门禁判分。
     */
    private static P3BScenario c07CapabilityRelevantWithGroundedArguments() {
        return scenario("P3B-C07", "C07", "参数齐全但当前并不需要调用能力",
                simpleGiven("p3b-c07-root", List.of(
                        new BrainClaim("goal", "p3b-c07-goal", "confirmed", 0.9)),
                        invokeMemorySearch(Map.of("query", "p3b-c07-query-text", "scope", "PROJECT")),
                        List.of("eval.decoy.read-only")),
                expect(Set.of("RESPOND_TO_USER", "REQUEST_USER_INPUT"),
                        Set.of("INVOKE_CAPABILITY"),
                        Map.of("answers", 1, "patches", 1, "nodes", 0),
                        Map.of("capabilityInvocations", 0, "relations", 0),
                        budget(1)),
                variants(3701L),
                new P3BOracle("C07", GateExpectation.NO_DETERMINISTIC_SURFACE, Set.of(), false,
                        null, true, true));
    }

    // -- C08:明确需要 capability,且参数齐全 ------------------------------------

    /**
     * 用户明确要求检索现有资料,且需要的能力可见、声明必填参数都在。
     * 这必须保持 eligible:不得为了压低调用率而制造新的 false negative。
     */
    private static P3BScenario c08CapabilityNeededWithArguments() {
        return scenario("P3B-C08", "C08", "参数齐全的能力调用必须被允许",
                simpleGiven("p3b-c08-root", List.of(
                        new BrainClaim("goal", "p3b-c08-goal", "confirmed", 0.9)),
                        invokeMemorySearch(Map.of(
                                "query", "p3b-c08-query-text", "scope", "PROJECT")),
                        List.of("eval.decoy.read-only")),
                expect(Set.of("INVOKE_CAPABILITY"),
                        Set.of("REQUEST_USER_INPUT", "WAIT"),
                        Map.of("answers", 1, "patches", 1, "nodes", 0),
                        Map.of("relations", 0),
                        budget(1)),
                variants(3801L),
                new P3BOracle("C08", GateExpectation.PASS, Set.of(), false,
                        "EXECUTED_NEW_OBSERVATION", true, true));
    }

    // -- C09:明确需要 capability,但缺少必要参数 -------------------------------

    /**
     * 需要检索,却**没有**给出 {@code memory.search} 声明的必填参数 query。
     * 现状:门禁放行 → 能力真正执行并以
     * {@code arguments.query must be non-blank} 失败,白白消耗一次调用。
     * 正确行为是让这类调用在 eligibility 边界就 fail-closed。
     */
    private static P3BScenario c09CapabilityNeededButMissingRequiredArgument() {
        return scenario("P3B-C09", "C09", "缺少声明必填参数的能力调用必须 fail-closed",
                simpleGiven("p3b-c09-root", List.of(
                        new BrainClaim("goal", "p3b-c09-goal", "confirmed", 0.9)),
                        invokeMemorySearch(Map.of("scope", "PROJECT")),
                        List.of("eval.decoy.read-only")),
                expect(Set.of("REQUEST_USER_INPUT"),
                        Set.of("INVOKE_CAPABILITY"),
                        Map.of("answers", 1, "patches", 1),
                        Map.of("capabilityInvocations", 0),
                        budget(1)),
                variants(3901L),
                new P3BOracle("C09", GateExpectation.VETO,
                        Set.of("MISSING_REQUIRED_CAPABILITY_ARGUMENT"), false,
                        null, true, true));
    }

    // -- C10:单条需求包含条件和例外 ---------------------------------------------

    /**
     * 一句话里同时有条件与例外,并显式留下一处未决口径。已明确的部分必须
     * 保留,未决的那一项才是 blocker,只就它发问一次。
     */
    private static P3BScenario c10ConditionAndException() {
        return scenario("P3B-C10", "C10", "条件与例外并存时只追问真正未决的一项",
                simpleGiven("p3b-c10-root", List.of(
                        new BrainClaim("constraint", "p3b-c10-general-rule", "confirmed", 0.9),
                        new BrainClaim("constraint", "p3b-c10-exception-rule", "confirmed", 0.9),
                        new BrainClaim("open_question", "p3b-c10-open-threshold", "unresolved", 0.9)),
                        requestUserInput("p3b-c10-threshold-question"),
                        List.of("eval.decoy.read-only")),
                expect(Set.of("REQUEST_USER_INPUT"),
                        Set.of("INVOKE_CAPABILITY", "WAIT"),
                        Map.of("answers", 1, "patches", 1, "nodes", 1),
                        Map.of("capabilityInvocations", 0, "relations", 0),
                        budget(0)),
                variants(31001L),
                new P3BOracle("C10", GateExpectation.PASS, Set.of(), false,
                        "PARKED_USER_INPUT", false, true));
    }

    // -- C11:多项约束互相冲突 ---------------------------------------------------

    private static P3BScenario c11ConflictingConstraints() {
        return scenario("P3B-C11", "C11", "互斥约束必须由用户取舍,不得绕开",
                simpleGiven("p3b-c11-root", List.of(
                        new BrainClaim("constraint", "p3b-c11-offline", "confirmed", 0.9),
                        new BrainClaim("constraint", "p3b-c11-realtime-sync", "confirmed", 0.9),
                        new BrainClaim("conflict", "p3b-c11-mutual-exclusion", "unresolved", 0.95)),
                        requestUserInput("p3b-c11-tradeoff-question"),
                        List.of("eval.decoy.read-only")),
                expect(Set.of("REQUEST_USER_INPUT"),
                        Set.of("INVOKE_CAPABILITY", "WAIT", "CREATE_NODE"),
                        Map.of("answers", 1, "patches", 1, "nodes", 1),
                        Map.of("capabilityInvocations", 0, "relations", 0),
                        budget(0)),
                variants(31101L),
                new P3BOracle("C11", GateExpectation.PASS, Set.of(), false,
                        "PARKED_USER_INPUT", true, true));
    }

    // -- C12:已解决的历史冲突 ---------------------------------------------------

    /**
     * 冲突在历史轮次已由用户取舍解决。当前状态足以直接回应,不得因"历史上
     * 曾不确定"而重新提问或重复创建澄清节点。已解决的冲突 claim 记为
     * {@code rejected},因此不再触发冲突暴露要求。
     */
    private static P3BScenario c12ResolvedHistoricalConflict() {
        return scenario("P3B-C12", "C12", "已解决的历史冲突不得重新提问",
                simpleGiven("p3b-c12-root", List.of(
                        new BrainClaim("constraint", "p3b-c12-chosen-tradeoff", "confirmed", 0.9),
                        new BrainClaim("conflict", "p3b-c12-superseded-conflict", "rejected", 0.9)),
                        respondToUser("p3b-c12-ack"), List.of("eval.decoy.read-only")),
                expect(Set.of("RESPOND_TO_USER"),
                        Set.of("REQUEST_USER_INPUT", "INVOKE_CAPABILITY", "CREATE_NODE"),
                        Map.of("answers", 1, "patches", 1, "nodes", 0),
                        Map.of("capabilityInvocations", 0, "relations", 0),
                        budget(0)),
                variants(31201L),
                new P3BOracle("C12", GateExpectation.PASS, Set.of(), false,
                        "TERMINAL_RESPONSE", false, true));
    }

    // -- C13:跨来源信息,不能误用旧来源 -----------------------------------------

    /**
     * 上下文里存在一个已作废的资源来源(旧口径)。回答必须只依据现行口径。
     * Runtime 在门禁层的机械保障是 refs 白名单(已由刻画测试覆盖);
     * "不得把作废来源当作现行事实"属于语义判断,本场景如实标注无确定性判据。
     */
    private static P3BScenario c13SupersededSource() {
        return scenario("P3B-C13", "C13", "作废来源不得被当作现行口径",
                new GivenSpec(
                        "p3b-c13-title",
                        List.of(
                                new GraphStep.CreateRootQuestion("p3b-c13-root", true),
                                new GraphStep.AttachResource("p3b-c13-superseded-source")),
                        new UserEvent.AnswerTip("p3b-c13-answer"),
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(new CapabilitySpec("eval.decoy.read-only", "NONE", true, Map.of())),
                        List.of(),
                        script(List.of(
                                new BrainClaim("constraint", "p3b-c13-current-scope", "confirmed", 0.9),
                                new BrainClaim("assumption", "p3b-c13-source-retired", "rejected", 0.9)),
                                respondToUser("p3b-c13-ack"), List.of("p3b-c13-known"), List.of())),
                expect(Set.of("RESPOND_TO_USER"),
                        Set.of("INVOKE_CAPABILITY", "CREATE_NODE"),
                        Map.of("answers", 1, "patches", 1, "nodes", 0),
                        Map.of("capabilityInvocations", 0, "relations", 0),
                        budget(0)),
                variants(31301L),
                new P3BOracle("C13", GateExpectation.NO_DETERMINISTIC_SURFACE, Set.of(), false,
                        null, false, true));
    }

    // -- C14:已持久化的 Answer/Claim,不得重复创建 Node -------------------------

    /**
     * 把当前答案原样再持久化成一个 KNOWLEDGE/NOTE 节点。Runtime 有确定性
     * 判据:{@code ANSWER_ALREADY_DURABLE}。
     */
    private static P3BScenario c14DuplicateDurableNode() {
        String answerSeed = "p3b-c14-answer";
        return scenario("P3B-C14", "C14", "重复当前答案的节点创建必须被否决",
                simpleGivenWithAnswer("p3b-c14-root", List.of(
                        new BrainClaim("goal", "p3b-c14-goal", "confirmed", 0.9)),
                        new BrainDecision("CREATE_NODE", Map.of(
                                "kind", "KNOWLEDGE",
                                "subtype", "NOTE",
                                "contentTextSeed", answerSeed)),
                        List.of("eval.decoy.read-only"), answerSeed),
                expect(Set.of("RESPOND_TO_USER", "REQUEST_USER_INPUT"),
                        Set.of("CREATE_NODE"),
                        Map.of("answers", 1, "patches", 1),
                        Map.of("nodes", 0, "capabilityInvocations", 0),
                        budget(0)),
                variants(31401L),
                new P3BOracle("C14", GateExpectation.VETO,
                        Set.of("ANSWER_ALREADY_DURABLE"), false, null, false, true));
    }

    // -- C15:无法由项目事实支持的回答 ------------------------------------------

    /**
     * 用户要求就一件项目里根本没有依据的事给结论。Runtime 对 RESPOND_TO_USER
     * 没有 grounding 判据(这个边界刻意只做"能执行/不能执行"),因此这是
     * 语义缺口,如实标注。
     */
    private static P3BScenario c15UnsupportedAssertion() {
        return scenario("P3B-C15", "C15", "无项目事实支持的回答:语义缺口",
                simpleGiven("p3b-c15-root", List.of(
                        new BrainClaim("open_question", "p3b-c15-unknown", "unresolved", 0.9)),
                        respondToUser("p3b-c15-ack"), List.of("eval.decoy.read-only")),
                expect(Set.of("RESPOND_TO_USER", "REQUEST_USER_INPUT"),
                        Set.of("INVOKE_CAPABILITY", "CREATE_NODE"),
                        Map.of("answers", 1, "patches", 1, "nodes", 0),
                        Map.of("capabilityInvocations", 0, "relations", 0),
                        budget(0)),
                variants(31501L),
                new P3BOracle("C15", GateExpectation.NO_DETERMINISTIC_SURFACE, Set.of(), false,
                        null, false, true));
    }

    // -- C16:存在真正的外部等待条件 ---------------------------------------------

    /**
     * 项目确实在等一个外部结果。{@code WAIT} 的语义证据存在,但
     * {@code agent-input.v2} 没有"待处理依赖"的表示,因此 Runtime 恒以
     * {@code NO_PENDING_DEPENDENCY} 拒绝 WAIT —— 这是**已知且被接受**的
     * false negative(既有 R4 报告 §9 已记录),本轮不改。
     */
    private static P3BScenario c16GenuineExternalWait() {
        return scenario("P3B-C16", "C16", "真实外部等待:WAIT 表示缺失导致的已知缺口",
                simpleGiven("p3b-c16-root", List.of(
                        new BrainClaim("constraint", "p3b-c16-pending-vendor", "unresolved", 0.9)),
                        new BrainDecision("WAIT", Map.of()),
                        List.of("eval.decoy.read-only")),
                expect(Set.of("WAIT"),
                        Set.of("INVOKE_CAPABILITY", "CREATE_NODE"),
                        Map.of("answers", 1, "patches", 1),
                        Map.of("nodes", 0, "capabilityInvocations", 0),
                        budget(0)),
                variants(31601L),
                new P3BOracle("C16", GateExpectation.VETO,
                        Set.of("NO_PENDING_DEPENDENCY"), true, "NO_EFFECT", false, true));
    }

    // -- C17:工具调用成功后,是否应继续或停止 ----------------------------------

    /**
     * 只读能力调用成功会留下可被下一个快照消费的观察 → 链**应当继续**,
     * 而不是就地停住。这里验证续跑裁决与"调用成功"的语义一致。
     */
    private static P3BScenario c17AfterSuccessfulCapability() {
        return scenario("P3B-C17", "C17", "只读能力调用成功后链应继续",
                simpleGiven("p3b-c17-root", List.of(
                        new BrainClaim("goal", "p3b-c17-goal", "confirmed", 0.9)),
                        new BrainDecision("INVOKE_CAPABILITY", Map.of(
                                "capabilityId", "eval.decoy.read-only",
                                "arguments", Map.of("nodeRef", "step:tip"))),
                        List.of("eval.decoy.read-only")),
                expect(Set.of("INVOKE_CAPABILITY"),
                        Set.of("REQUEST_USER_INPUT", "WAIT", "CREATE_NODE"),
                        Map.of("answers", 1, "patches", 1, "nodes", 0),
                        Map.of("relations", 0),
                        budget(1)),
                variants(31701L),
                new P3BOracle("C17", GateExpectation.PASS, Set.of(), false,
                        "EXECUTED_NEW_OBSERVATION", true, true));
    }

    // -- C18:需要用户审批的合法动作 ---------------------------------------------

    /**
     * 本地持久化副作用的能力必须等用户确认:proposal 进入待审批,链在此挂起,
     * 且**绝不**先执行一次。
     */
    private static P3BScenario c18ApprovalRequiredAction() {
        return scenario("P3B-C18", "C18", "本地持久化能力必须由用户审批",
                new GivenSpec(
                        "p3b-c18-title",
                        List.of(new GraphStep.CreateRootQuestion("p3b-c18-root", true)),
                        new UserEvent.AnswerTip("p3b-c18-answer"),
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(new CapabilitySpec("eval.high-risk.local-durable",
                                "LOCAL_DURABLE", true, Map.of())),
                        List.of(),
                        script(List.of(new BrainClaim("goal", "p3b-c18-goal", "confirmed", 0.9)),
                                new BrainDecision("INVOKE_CAPABILITY", Map.of(
                                        "capabilityId", "eval.high-risk.local-durable",
                                        "arguments", Map.of("nodeRef", "step:tip"))),
                                List.of("p3b-c18-known"), List.of())),
                expect(Set.of("INVOKE_CAPABILITY"),
                        Set.of("WAIT", "RESPOND_TO_USER"),
                        Map.of("answers", 1, "patches", 1, "nodes", 0, "proposals", 1),
                        Map.of("capabilityInvocations", 0, "relations", 0),
                        budget(1)),
                variants(31801L),
                new P3BOracle("C18", GateExpectation.PASS, Set.of(), false,
                        "PARKED_APPROVAL", false, true));
    }

    // -- 构造辅助 ----------------------------------------------------------------

    private static P3BScenario scenario(String scenarioId, String categoryId, String description,
                                        GivenSpec given, ExpectSpec expect,
                                        List<VariantSpec> variants, P3BOracle oracle) {
        ScenarioDefinition definition = new ScenarioDefinition(
                scenarioId, "1", description, given, expect, variants);
        definition.validate();
        return new P3BScenario(scenarioId, oracle, definition);
    }

    /** 最简回答周期:一个根问题 + 一次回答。 */
    private static GivenSpec simpleGiven(String rootSeed, List<BrainClaim> claims,
                                         BrainDecision decision, List<String> decoyCapabilityIds) {
        return givenWithAnswer(rootSeed, "answer", decoyCapabilityIds, claims, decision);
    }

    /** 让事件自由文本与节点创建内容使用同一种子(C14 的重复持久化探针)。 */
    private static GivenSpec simpleGivenWithAnswer(String rootSeed, List<BrainClaim> claims,
                                                   BrainDecision decision,
                                                   List<String> decoyCapabilityIds,
                                                   String answerSeed) {
        return givenWithAnswer(rootSeed, answerSeed, decoyCapabilityIds, claims, decision);
    }

    private static GivenSpec givenWithAnswer(String rootSeed, String answerSeed,
                                             List<String> visibleCapabilityIds,
                                             List<BrainClaim> claims, BrainDecision decision) {
        List<CapabilitySpec> capabilities = new ArrayList<>();
        for (String capabilityId : visibleCapabilityIds) {
            capabilities.add(new CapabilitySpec(capabilityId, "NONE", true, Map.of()));
        }
        return new GivenSpec(
                "p3b-title",
                List.of(new GraphStep.CreateRootQuestion(rootSeed, true)),
                new UserEvent.AnswerTip(answerSeed),
                new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                new FocusContextSpec(null, null),
                capabilities,
                List.of(),
                script(claims, decision, List.of("p3b-known"), List.of()));
    }

    private static BrainScript script(List<BrainClaim> claims, BrainDecision decision,
                                      List<String> known, List<String> conflicts) {
        return new BrainScript(claims, decision, known, conflicts);
    }

    private static BrainDecision requestUserInput(String questionTextSeed) {
        return new BrainDecision("REQUEST_USER_INPUT", Map.of(
                "questionTextSeed", questionTextSeed,
                "options", List.of(Map.of("label", "State the rule explicitly")),
                "allowFreeAnswer", true));
    }

    private static BrainDecision respondToUser(String messageSeed) {
        return new BrainDecision("RESPOND_TO_USER", Map.of("message", messageSeed));
    }

    private static BrainDecision invokeMemorySearch(Map<String, Object> arguments) {
        return new BrainDecision("INVOKE_CAPABILITY", Map.of(
                "capabilityId", "memory.search",
                "arguments", arguments));
    }

    private static ExpectSpec expect(Set<String> acceptable,
                                     Set<String> forbidden,
                                     Map<String, Integer> expectedDelta,
                                     Map<String, Integer> forbiddenDelta,
                                     CallBudget budget) {
        return new ExpectSpec(NO_INVARIANTS, List.of(), acceptable, forbidden,
                expectedDelta, forbiddenDelta, budget);
    }

    private static CallBudget budget(int maxCapabilityCalls) {
        return new CallBudget(List.of("STATE_UPDATE", "DECISION"), 2, 2, 1, 0,
                maxCapabilityCalls, 0);
    }

    /** 每个场景一个 base + 一个 holdout(改写/打乱),用于未参与调优的独立复测。 */
    private static List<VariantSpec> variants(long seed) {
        return List.of(
                VariantSpec.base("base", seed),
                VariantSpec.builder("holdout", seed + 1)
                        .paraphraseIndex(1)
                        .shuffleIrrelevantContext(true)
                        .usage(VariantSpec.Usage.HOLDOUT)
                        .build());
    }
}
