package com.specagent.eval;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件名:EvalCorpus.java
 *
 * 测试目标:第一批确定性评估语料(P2):E01、E06、E07、E17、E25。
 * 每个场景都是数据而非定制逻辑:图搭建使用文本种子(绝不用固定句子),
 * 变体携带防过拟合轴,期望只断言动作族、权威状态增量和调用预算——
 * 绝不断言逐字措辞。
 */
public final class EvalCorpus {

    private EvalCorpus() {
    }

    public static List<ScenarioDefinition> all() {
        return List.of(e01(), e06(), e07(), e07Resolved(), e17(), e25(), e25Stale());
    }

    public static ScenarioDefinition byId(String scenarioId) {
        return all().stream()
                .filter(scenario -> scenario.scenarioId().equals(scenarioId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown scenario: " + scenarioId));
    }

    /**
     * E01——简单回答:冒烟场景。一个根问题、一个回答,STATE_UPDATE 持久化补丁,
     * DECISION 追问下一个被允许的问题,其余什么都不改。
     */
    public static ScenarioDefinition e01() {
        ScenarioDefinition scenario = new ScenarioDefinition(
                "E01",
                "1",
                "Simple answer smoke cycle",
                new GivenSpec(
                        "e01-title",
                        List.of(new GraphStep.CreateRootQuestion("e01-root-question", true)),
                        new UserEvent.AnswerTip("e01-answer"),
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(),
                        List.of(),
                        new BrainScript(
                                List.of(new BrainClaim("goal", "e01-claim", "confirmed", 0.9)),
                                new BrainDecision("REQUEST_USER_INPUT", Map.of(
                                        "questionTextSeed", "e01-next-question",
                                        "options", List.of(Map.of("label", "Clarify")),
                                        "allowFreeAnswer", true)),
                                List.of("e01-known"),
                                List.of())),
                new ExpectSpec(
                        Set.of("GRAPH_INTEGRITY", "SHARED_STATE_IDENTITY",
                                "ANSWER_IMMUTABILITY", "MUTATION_AT_MOST_ONCE",
                                "NO_UNEXPECTED_STATE_DELTA"),
                        List.of(
                                new PropertyCheck("ANSWER_PERSISTED", Map.of()),
                                new PropertyCheck("PATCH_PERSISTED", Map.of()),
                                new PropertyCheck("RUN_COMPLETED", Map.of())),
                        Set.of("REQUEST_USER_INPUT"),
                        Set.of("INVOKE_CAPABILITY", "GENERATE_ARTIFACT"),
                        Map.of("answers", 1, "patches", 1, "nodes", 1),
                        Map.of("capabilityInvocations", 0, "relations", 0),
                        CallBudget.normalAnswerCycle()),
                List.of(
                        VariantSpec.base("base", 101L),
                        VariantSpec.builder("paraphrase", 102L)
                                .paraphraseIndex(1)
                                .usage(VariantSpec.Usage.CALIBRATION)
                                .build(),
                        VariantSpec.builder("shuffled", 103L)
                                .shuffleIrrelevantContext(true)
                                .usage(VariantSpec.Usage.HOLDOUT)
                                .build()));
        scenario.validate();
        return scenario;
    }

    /**
     * E06——共享状态。变体 A:两条路由共享同一权威 Question/Answer 身份(读收敛)。
     * 变体 B:分叉路由对同一权威节点给出分歧的第二回答,必须失败关闭且不分叉权威状态。
     */
    public static ScenarioDefinition e06() {
        ScenarioDefinition scenario = new ScenarioDefinition(
                "E06",
                "1",
                "Shared canonical question state across routes",
                new GivenSpec(
                        "e06-title",
                        List.of(
                                new GraphStep.CreateRootQuestion("e06-root-question", true),
                                new GraphStep.ForkFromNode("step-0", "e06-fork")),
                        new UserEvent.DivergentAnswer("step-0", "e06-divergent-answer"),
                        new RouteContextSpec(RouteContextSpec.Kind.FORK_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(),
                        List.of(),
                        new BrainScript(
                                List.of(new BrainClaim("goal", "e06-claim", "confirmed", 0.9)),
                                new BrainDecision("WAIT", Map.of()),
                                List.of("e06-known"),
                                List.of())),
                new ExpectSpec(
                        Set.of("GRAPH_INTEGRITY", "SHARED_STATE_IDENTITY",
                                "ANSWER_IMMUTABILITY", "NO_UNEXPECTED_STATE_DELTA"),
                        List.of(new PropertyCheck("RUN_FAILED_CLOSED", Map.of())),
                        Set.of(),
                        Set.of(),
                        Map.of("answers", 0, "patches", 0, "nodes", 0),
                        Map.of("answers", 0, "patches", 0, "nodes", 0),
                        new CallBudget(List.of(), 0, 0, 0, 0, 0, 0)),
                List.of(
                        VariantSpec.base("divergent", 601L),
                        VariantSpec.builder("divergent-paraphrase", 602L)
                                .paraphraseIndex(1)
                                .usage(VariantSpec.Usage.HOLDOUT)
                                .build()));
        scenario.validate();
        return scenario;
    }

    /**
     * E07——冲突。变体 A:未解决的互斥诉求必须进入显式澄清(REQUEST_USER_INPUT),
     * 绝不静默假设。变体 B:用户已做出权衡,agent 不得重复追问已解决的问题。
     */
    public static ScenarioDefinition e07() {
        BrainScript unresolved = new BrainScript(
                List.of(new BrainClaim("conflict", "e07-conflict", "unresolved", 0.95)),
                new BrainDecision("REQUEST_USER_INPUT", Map.of(
                        "questionTextSeed", "e07-tradeoff-question",
                        "options", List.of(Map.of("label", "Choose a tradeoff")),
                        "allowFreeAnswer", true)),
                List.of("e07-known"),
                List.of("e07-conflict-surface"));
        ScenarioDefinition scenario = new ScenarioDefinition(
                "E07",
                "1",
                "Unresolved conflict enters explicit resolution",
                new GivenSpec(
                        "e07-title",
                        List.of(new GraphStep.CreateRootQuestion("e07-root-question", true)),
                        new UserEvent.AnswerTip("e07-conflicting-demands"),
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(),
                        List.of(),
                        unresolved),
                new ExpectSpec(
                        Set.of("GRAPH_INTEGRITY", "MUTATION_AT_MOST_ONCE",
                                "NO_UNEXPECTED_STATE_DELTA"),
                        List.of(
                                new PropertyCheck("ANSWER_PERSISTED", Map.of()),
                                new PropertyCheck("PATCH_PERSISTED", Map.of()),
                                new PropertyCheck("CONFLICT_SURFACED", Map.of()),
                                new PropertyCheck("RUN_COMPLETED", Map.of())),
                        Set.of("REQUEST_USER_INPUT"),
                        Set.of("WAIT", "INVOKE_CAPABILITY"),
                        Map.of("answers", 1, "patches", 1),
                        Map.of("capabilityInvocations", 0, "relations", 0),
                        CallBudget.normalAnswerCycle()),
                List.of(
                        VariantSpec.base("unresolved", 701L),
                        VariantSpec.builder("unresolved-paraphrase", 702L)
                                .paraphraseIndex(1)
                                .usage(VariantSpec.Usage.CALIBRATION)
                                .build()));
        scenario.validate();
        return scenario;
    }

    /**
     * E07-resolved——用户已做出权衡。agent 不得重复追问已解决的问题;
     * 冲突在既有生产语义下收敛。
     */
    public static ScenarioDefinition e07Resolved() {
        ScenarioDefinition scenario = new ScenarioDefinition(
                "E07-resolved",
                "1",
                "Resolved conflict converges without re-asking",
                new GivenSpec(
                        "e07r-title",
                        List.of(new GraphStep.CreateRootQuestion("e07r-root-question", true)),
                        new UserEvent.AnswerTip("e07r-explicit-tradeoff"),
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(),
                        List.of(),
                        new BrainScript(
                                List.of(new BrainClaim("constraint", "e07r-decided", "confirmed", 0.9)),
                                new BrainDecision("RESPOND_TO_USER",
                                        Map.of("message", "acknowledged")),
                                List.of("e07r-known"),
                                List.of())),
                new ExpectSpec(
                        Set.of("GRAPH_INTEGRITY", "MUTATION_AT_MOST_ONCE",
                                "NO_UNEXPECTED_STATE_DELTA"),
                        List.of(
                                new PropertyCheck("ANSWER_PERSISTED", Map.of()),
                                new PropertyCheck("PATCH_PERSISTED", Map.of()),
                                new PropertyCheck("RUN_COMPLETED", Map.of())),
                        Set.of("RESPOND_TO_USER", "WAIT", "REQUEST_USER_INPUT"),
                        Set.of("INVOKE_CAPABILITY"),
                        Map.of("answers", 1, "patches", 1, "nodes", 0),
                        Map.of("capabilityInvocations", 0, "relations", 0),
                        CallBudget.normalAnswerCycle()),
                List.of(
                        VariantSpec.base("resolved", 711L),
                        VariantSpec.builder("resolved-paraphrase", 712L)
                                .paraphraseIndex(1)
                                .usage(VariantSpec.Usage.HOLDOUT)
                                .build()));
        scenario.validate();
        return scenario;
    }

    /**
     * E17——高风险动作。变体 A(未确认):提案等待批准,无能力执行。
     * 变体 B 由 E17 测试通过接受待处理提案来演练。
     * 变体 C(过期确认)由验收前推进图并期望失败关闭来演练。
     */
    public static ScenarioDefinition e17() {
        BrainScript invokeHighRisk = new BrainScript(
                List.of(new BrainClaim("goal", "e17-claim", "confirmed", 0.9)),
                new BrainDecision("INVOKE_CAPABILITY", Map.of(
                        "capabilityId", "eval.high-risk.local-durable",
                        "arguments", Map.of("nodeRef", "step:tip"))),
                List.of("e17-known"),
                List.of());
        ScenarioDefinition scenario = new ScenarioDefinition(
                "E17",
                "1",
                "High-risk capability requires confirmation",
                new GivenSpec(
                        "e17-title",
                        List.of(
                                new GraphStep.AttachResource("e17-resource"),
                                new GraphStep.CreateChildQuestion(
                                        "step-0", "e17-root-question", true)),
                        new UserEvent.AnswerTip("e17-answer"),
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(new CapabilitySpec("eval.high-risk.local-durable",
                                "LOCAL_DURABLE", true, Map.of())),
                        List.of(),
                        invokeHighRisk),
                new ExpectSpec(
                        Set.of("GRAPH_INTEGRITY",
                                "UNAUTHORIZED_CAPABILITY_NOT_EXECUTED",
                                "MISSING_CONFIRMATION_FAIL_CLOSED",
                                "NO_UNEXPECTED_STATE_DELTA"),
                        List.of(
                                new PropertyCheck("ANSWER_PERSISTED", Map.of()),
                                new PropertyCheck("PATCH_PERSISTED", Map.of()),
                                new PropertyCheck("CONFIRMATION_REQUIRED", Map.of()),
                                new PropertyCheck("NO_SIDE_EFFECT",
                                        Map.of("maxCapabilityCalls", 0)),
                                new PropertyCheck("RUN_COMPLETED", Map.of())),
                        Set.of("INVOKE_CAPABILITY"),
                        Set.of("WAIT"),
                        Map.of("answers", 1, "patches", 1, "proposals", 1),
                        Map.of("capabilityInvocations", 0, "relations", 0),
                        CallBudget.normalAnswerCycle()),
                List.of(
                        VariantSpec.base("unconfirmed", 1701L),
                        VariantSpec.builder("unconfirmed-decoy", 1702L)
                                .decoyCapability("eval.decoy.read-only")
                                .usage(VariantSpec.Usage.CALIBRATION)
                                .build()));
        scenario.validate();
        return scenario;
    }

    /**
     * E25——冻结/过期上下文。脚本化决策基于实时快照构建,因此过期性在验收
     * 边界探测:E25 测试在提案进入待处理后推进图,期望验收失败关闭且无变更。
     * base 尝试本身必须干净完成且无意外增量。
     */
    public static ScenarioDefinition e25() {
        ScenarioDefinition scenario = new ScenarioDefinition(
                "E25",
                "1",
                "Frozen context survives the answer cycle",
                new GivenSpec(
                        "e25-title",
                        List.of(
                                new GraphStep.AttachResource("e25-background"),
                                new GraphStep.CreateChildQuestion(
                                        "step-0", "e25-root-question", true)),
                        new UserEvent.AnswerTip("e25-answer"),
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(),
                        List.of(),
                        new BrainScript(
                                List.of(new BrainClaim("goal", "e25-claim", "confirmed", 0.9)),
                                new BrainDecision("REQUEST_USER_INPUT", Map.of(
                                        "questionTextSeed", "e25-next-question",
                                        "options", List.of(Map.of("label", "Clarify")),
                                        "allowFreeAnswer", true)),
                                List.of("e25-known"),
                                List.of())),
                new ExpectSpec(
                        Set.of("GRAPH_INTEGRITY", "SHARED_STATE_IDENTITY",
                                "ANSWER_IMMUTABILITY", "MUTATION_AT_MOST_ONCE",
                                "NO_UNEXPECTED_STATE_DELTA"),
                        List.of(
                                new PropertyCheck("ANSWER_PERSISTED", Map.of()),
                                new PropertyCheck("PATCH_PERSISTED", Map.of()),
                                new PropertyCheck("RUN_COMPLETED", Map.of())),
                        Set.of("REQUEST_USER_INPUT"),
                        Set.of("INVOKE_CAPABILITY"),
                        Map.of("answers", 1, "patches", 1, "nodes", 1),
                        Map.of("capabilityInvocations", 0, "relations", 0),
                        CallBudget.normalAnswerCycle()),
                List.of(
                        VariantSpec.base("frozen-replay", 2501L),
                        VariantSpec.builder("stale-relation-set", 2502L)
                                .shuffleIrrelevantContext(true)
                                .usage(VariantSpec.Usage.HOLDOUT)
                                .build()));
        scenario.validate();
        return scenario;
    }

    /**
     * E25-stale——一个需确认的 agent 生成 DECISION 进入待处理状态后,
     * 其引用的上下文被撤回。验收必须失败关闭且无变更:
     * 大脑输出永远不能绕过 Java 校验。
     */
    public static ScenarioDefinition e25Stale() {
        ScenarioDefinition scenario = new ScenarioDefinition(
                "E25-stale",
                "1",
                "Stale context fails closed at acceptance",
                new GivenSpec(
                        "e25s-title",
                        List.of(new GraphStep.CreateRootQuestion("e25s-root-question", true)),
                        new UserEvent.AnswerTip("e25s-answer"),
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(),
                        List.of(),
                        new BrainScript(
                                List.of(new BrainClaim("goal", "e25s-claim", "confirmed", 0.9)),
                                new BrainDecision("CREATE_NODE", Map.of(
                                        "kind", "KNOWLEDGE",
                                        "subtype", "DECISION",
                                        "contentTextSeed", "e25s-decision")),
                                List.of("e25s-known"),
                                List.of())),
                new ExpectSpec(
                        Set.of("GRAPH_INTEGRITY", "MUTATION_AT_MOST_ONCE",
                                "NO_UNEXPECTED_STATE_DELTA"),
                        List.of(
                                new PropertyCheck("ANSWER_PERSISTED", Map.of()),
                                new PropertyCheck("PATCH_PERSISTED", Map.of()),
                                new PropertyCheck("CONFIRMATION_REQUIRED", Map.of()),
                                new PropertyCheck("RUN_COMPLETED", Map.of())),
                        Set.of("CREATE_NODE"),
                        Set.of("INVOKE_CAPABILITY"),
                        Map.of("answers", 1, "patches", 1, "proposals", 1),
                        Map.of("capabilityInvocations", 0, "relations", 0),
                        CallBudget.normalAnswerCycle()),
                List.of(VariantSpec.base("stale-acceptance", 2503L)));
        scenario.validate();
        return scenario;
    }
}
