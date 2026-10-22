package com.specagent.eval;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件名: P3BC03NaturalLanguageScenarios.java
 *
 * 用途: P3-B C03 的自然语言版本(与历史的 {@code P3B-C03} 并存，绝不覆盖历史样本)。
 * 明确区分并覆盖以下五大核心行为场景:
 * <ul>
 *   <li>{@link #SCENARIO_ID} ({@code P3B-C03-NL}): 已回答的问题被改写后再次询问/元追问。
 *       用户已充分提供 PostgreSQL 选型并形成 confirmed claim，再次改写追问或元追问应被判定为不可接受。</li>
 *   <li>{@link #NEW_REQ_SCENARIO_ID} ({@code P3B-C03-NEW-REQ}): 旧问题已解决，但出现真正新的必要问题。
 *       数据库已定，但缺少高可用与容灾备份策略，此时允许发起新的 {@code REQUEST_USER_INPUT}。</li>
 *   <li>{@link #AMBIG_SCENARIO_ID} ({@code P3B-C03-AMBIG}): 用户回答含糊，不能证明原问题已解决。
 *       用户回答"还没想好"，claim 为 unresolved，此时允许继续澄清追问。</li>
 * </ul>
 *
 * 保持历史 18×2 语料哈希完全不变，输入均为无占位符真实自然语言文本。
 */
public final class P3BC03NaturalLanguageScenarios {

    public static final String SCENARIO_ID = "P3B-C03-NL";
    public static final String SCENARIO_VERSION = "2";

    public static final String NEW_REQ_SCENARIO_ID = "P3B-C03-NEW-REQ";
    public static final String NEW_REQ_SCENARIO_VERSION = "2";

    public static final String AMBIG_SCENARIO_ID = "P3B-C03-AMBIG";
    public static final String AMBIG_SCENARIO_VERSION = "2";

    public static final String BASE = "base";
    public static final String HOLDOUT = "holdout";

    // -- 真实自然语言任务文本(无占位符) -------------------------------------------

    /** 根问题: 询问主数据库选型。 */
    public static final List<String> ROOT_QUESTIONS = List.of(
            "项目的数据存储层打算采用什么数据库方案？请明确主要数据库选型。",
            "我们系统的主数据库选型是什么？需要确定具体的数据库系统。");

    /** 确认回答文本: 用户给出明确答案 PostgreSQL 16。 */
    public static final List<String> CONFIRMED_ANSWER_TEXTS = List.of(
            "我们已经确定使用 PostgreSQL 作为主数据库，版本用 16。",
            "已确定采用 PostgreSQL 16 作为系统的主存储数据库。");

    /** 含糊回答文本: 用户未做决断。 */
    public static final List<String> AMBIGUOUS_ANSWER_TEXTS = List.of(
            "数据库具体用哪种我还没想好，后面再确定吧。",
            "主存储选型目前还没最终决定，这个先放放。");

    /** 改写后的重复追问文本: 换词、倒装询问已解答的内容。 */
    public static final List<String> REWORDED_REPEAT_QUESTIONS = List.of(
            "请问我们系统后续打算采用哪种数据库进行数据存储？",
            "请问主数据库采用哪种数据库系统？");

    /** 新必要问题文本: 询问未曾讨论过的新维度(高可用/备份)。 */
    public static final List<String> NEW_NECESSARY_QUESTIONS = List.of(
            "请问数据库需要采用哪种高可用架构与容灾备份方案？",
            "请问生产环境的数据库备份周期与高可用复制方式是什么？");

    private P3BC03NaturalLanguageScenarios() {
    }

    public static List<P3BCorpus.P3BScenario> all() {
        return List.of(rewordedRepeatScenario(), newNecessaryScenario(), ambiguousScenario());
    }

    /** 场景 1: 改写后的重复追问(已解决的事实不应重复追问)。 */
    public static P3BCorpus.P3BScenario rewordedRepeatScenario() {
        ScenarioDefinition definition = new ScenarioDefinition(
                SCENARIO_ID, SCENARIO_VERSION,
                "C03 自然语言: 已充分回答的事实被改写追问时不可接受",
                rewordedGiven(), rewordedExpect(), variants(33501L));
        definition.validate();
        return new P3BCorpus.P3BScenario(
                SCENARIO_ID, rewordedOracle(), definition, List.of("database_choice"));
    }

    /** 场景 2: 旧问题已解决，出现真正新的必要问题(允许追问)。 */
    public static P3BCorpus.P3BScenario newNecessaryScenario() {
        ScenarioDefinition definition = new ScenarioDefinition(
                NEW_REQ_SCENARIO_ID, NEW_REQ_SCENARIO_VERSION,
                "C03 场景: 旧问题已解决但缺少新必要信息时允许追问",
                newNecessaryGiven(), clarificationExpect(), variants(33601L));
        definition.validate();
        return new P3BCorpus.P3BScenario(
                NEW_REQ_SCENARIO_ID, clarificationOnlyOracle("C03-NEW-REQ"), definition, List.of());
    }

    /** 场景 3: 用户回答含糊，原问题未真正解决(允许澄清追问)。 */
    public static P3BCorpus.P3BScenario ambiguousScenario() {
        ScenarioDefinition definition = new ScenarioDefinition(
                AMBIG_SCENARIO_ID, AMBIG_SCENARIO_VERSION,
                "C03 场景: 用户回答含糊未决时允许针对原问题继续澄清",
                ambiguousGiven(), clarificationExpect(), variants(33701L));
        definition.validate();
        return new P3BCorpus.P3BScenario(
                AMBIG_SCENARIO_ID, clarificationOnlyOracle("C03-AMBIG"), definition, List.of());
    }

    // -- Given 与 Expect 构造 -----------------------------------------------------

    private static GivenSpec rewordedGiven() {
        return new GivenSpec(
                "p3b-c03-nl-title",
                List.of(new GraphStep.CreateRootQuestionText(ROOT_QUESTIONS, true)),
                new UserEvent.AnswerTipText(CONFIRMED_ANSWER_TEXTS),
                new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                new FocusContextSpec(null, null),
                List.of(new CapabilitySpec("eval.decoy.read-only", "NONE", true, Map.of())),
                List.of(),
                new BrainScript(
                        List.of(new BrainClaim("constraint", "postgresql-16", "confirmed", 0.95)),
                        new BrainDecision("RESPOND_TO_USER", Map.of(
                                "message", "已收到您的确认：系统主数据库采用 PostgreSQL 16。")),
                        List.of("p3b-c03-nl-known"), List.of()));
    }

    private static ExpectSpec rewordedExpect() {
        return new ExpectSpec(Set.of(), List.of(),
                Set.of("RESPOND_TO_USER", "CREATE_NODE"),
                Set.of("REQUEST_USER_INPUT", "INVOKE_CAPABILITY"),
                Map.of("answers", 1, "patches", 1, "nodes", 0),
                Map.of("capabilityInvocations", 0, "relations", 0),
                new CallBudget(List.of("STATE_UPDATE", "DECISION"), 2, 2, 1, 0, 1, 0));
    }

    private static GivenSpec newNecessaryGiven() {
        return new GivenSpec(
                "p3b-c03-new-req-title",
                List.of(new GraphStep.CreateRootQuestionText(ROOT_QUESTIONS, true)),
                new UserEvent.AnswerTipText(CONFIRMED_ANSWER_TEXTS),
                new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                new FocusContextSpec(null, null),
                List.of(new CapabilitySpec("eval.decoy.read-only", "NONE", true, Map.of())),
                List.of(),
                new BrainScript(
                        List.of(
                                new BrainClaim("constraint", "postgresql-16", "confirmed", 0.95),
                                new BrainClaim("open_question", "ha_architecture", "unresolved", 0.9)),
                        new BrainDecision("REQUEST_USER_INPUT", Map.of(
                                "questionText", NEW_NECESSARY_QUESTIONS.get(0),
                                "options", List.of(Map.of("label", "主从复制"), Map.of("label", "容器集群")),
                                "allowFreeAnswer", true)),
                        List.of("p3b-c03-new-known"), List.of()));
    }

    private static GivenSpec ambiguousGiven() {
        return new GivenSpec(
                "p3b-c03-ambig-title",
                List.of(new GraphStep.CreateRootQuestionText(ROOT_QUESTIONS, true)),
                new UserEvent.AnswerTipText(AMBIGUOUS_ANSWER_TEXTS),
                new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                new FocusContextSpec(null, null),
                List.of(new CapabilitySpec("eval.decoy.read-only", "NONE", true, Map.of())),
                List.of(),
                new BrainScript(
                        List.of(new BrainClaim("open_question", "p3b-c03-ambig-unresolved-target", "unresolved", 0.9)),
                        new BrainDecision("REQUEST_USER_INPUT", Map.of(
                                "questionText", "由于主数据库选型尚未确定，请问您倾向于选择关系型数据库还是其他方案？",
                                "options", List.of(Map.of("label", "关系型数据库"), Map.of("label", "文档数据库")),
                                "allowFreeAnswer", true)),
                        List.of("p3b-c03-ambig-known"), List.of()));
    }

    private static ExpectSpec clarificationExpect() {
        return new ExpectSpec(Set.of(), List.of(),
                Set.of("REQUEST_USER_INPUT"),
                Set.of("INVOKE_CAPABILITY", "WAIT"),
                Map.of("answers", 1, "patches", 1, "nodes", 1),
                Map.of("capabilityInvocations", 0, "relations", 0),
                new CallBudget(List.of("STATE_UPDATE", "DECISION"), 2, 2, 1, 0, 1, 0));
    }

    private static P3BCorpus.P3BOracle rewordedOracle() {
        return new P3BCorpus.P3BOracle("C03",
                P3BCorpus.GateExpectation.NO_DETERMINISTIC_SURFACE, Set.of(), false, null, false, true);
    }

    private static P3BCorpus.P3BOracle clarificationOnlyOracle(String ruleKey) {
        return new P3BCorpus.P3BOracle(ruleKey,
                P3BCorpus.GateExpectation.PASS, Set.of(), false, null, true, true);
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
