package com.specagent.eval;

import com.specagent.agent.protocol.AgentEvent;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文件名:EvalCorpusBatch2.java
 *
 * 测试目标:第二批评估语料(P2 Phase 2):E02、E05、E08、E10、E11、E12、
 * E13、E19、E22-wait、E24。
 *
 * 与第一批相同的契约:每个场景都是数据(文本种子,绝不用固定句子),
 * 变体携带防过拟合轴,期望只断言动作族、权威状态增量和调用预算——绝不断言
 * 逐字措辞。每个场景先通过 B-fast(脚本化)才进入 live 基线语料,
 * 保证 live 红灯永远代表真实行为问题,而非场景本身畸形。
 *
 * 推迟到后续轮次(需要新的 UserEvent 种类或故障语义证明):
 * E03 回答修订(CONTINUE)、E04 非尖端继续(新路由上下文)、E09 路由规划
 * (CREATE_ROUTE 拒绝形态)、E22 冲突下的不当 WAIT、E23 畸形 Provider 输出。
 */
public final class EvalCorpusBatch2 {

    private EvalCorpusBatch2() {
    }

    public static List<ScenarioDefinition> all() {
        return List.of(e02(), e05(), e08(), e09(), e10(), e11(), e12(), e13(), e19(),
                e22Wait(), e24());
    }

    public static ScenarioDefinition byId(String scenarioId) {
        return all().stream()
                .filter(scenario -> scenario.scenarioId().equals(scenarioId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown scenario: " + scenarioId));
    }

    /**
     * E02——歧义澄清:模糊的回答仍走完整循环,落到一个显式的澄清提问,
     * 绝不静默假设。与 E01 相同的循环形态,只是种子带歧义色彩。
     */
    public static ScenarioDefinition e02() {
        ScenarioDefinition scenario = new ScenarioDefinition(
                "E02",
                "1",
                "Vague answer enters explicit clarification",
                new GivenSpec(
                        "e02-title",
                        List.of(new GraphStep.CreateRootQuestion("e02-vague-question", true)),
                        new UserEvent.AnswerTip("e02-vague-answer"),
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(),
                        List.of(),
                        new BrainScript(
                                List.of(new BrainClaim("goal", "e02-claim", "assumed", 0.5)),
                                new BrainDecision("REQUEST_USER_INPUT", Map.of(
                                        "questionTextSeed", "e02-clarifying-question",
                                        "options", List.of(Map.of("label", "Clarify")),
                                        "allowFreeAnswer", true)),
                                List.of("e02-known"),
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
                        VariantSpec.base("vague", 201L),
                        VariantSpec.builder("vague-paraphrase", 202L)
                                .paraphraseIndex(1)
                                .usage(VariantSpec.Usage.CALIBRATION)
                                .build(),
                        VariantSpec.builder("vague-shuffled", 203L)
                                .shuffleIrrelevantContext(true)
                                .usage(VariantSpec.Usage.HOLDOUT)
                                .build()));
        scenario.validate();
        return scenario;
    }

    /**
     * E05——兄弟路由隔离:分叉后在活动路由上回答,兄弟路由不受污染——
     * 全项目恰好存在一个权威回答,且回答循环不创建新路由。
     */
    public static ScenarioDefinition e05() {
        ScenarioDefinition scenario = new ScenarioDefinition(
                "E05",
                "1",
                "Answer on active route does not pollute sibling route",
                new GivenSpec(
                        "e05-title",
                        List.of(
                                new GraphStep.CreateRootQuestion("e05-root-question", true),
                                new GraphStep.ForkFromNode("step-0", "e05-fork"),
                                new GraphStep.CreateChildQuestion(
                                        "step-0", "e05-child-question", true)),
                        new UserEvent.AnswerTip("e05-answer"),
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(),
                        List.of(),
                        new BrainScript(
                                List.of(new BrainClaim("goal", "e05-claim", "confirmed", 0.9)),
                                new BrainDecision("REQUEST_USER_INPUT", Map.of(
                                        "questionTextSeed", "e05-next-question",
                                        "options", List.of(Map.of("label", "Clarify")),
                                        "allowFreeAnswer", true)),
                                List.of("e05-known"),
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
                        Map.of("answers", 1, "patches", 1, "nodes", 1, "routes", 0),
                        Map.of("capabilityInvocations", 0, "relations", 0),
                        CallBudget.normalAnswerCycle()),
                List.of(
                        VariantSpec.base("sibling", 501L),
                        VariantSpec.builder("sibling-paraphrase", 502L)
                                .paraphraseIndex(1)
                                .usage(VariantSpec.Usage.HOLDOUT)
                                .build()));
        scenario.validate();
        return scenario;
    }

    /**
     * E08——冲突委托:用户在本次回答中显式授权了权衡,agent 据此记录一个
     * KNOWLEDGE/DECISION 节点。模型生成的 DECISION 属于确认意图,总是需要
     * 确认——尝试以待审批结束,无任何副作用。
     */
    public static ScenarioDefinition e08() {
        ScenarioDefinition scenario = new ScenarioDefinition(
                "E08",
                "1",
                "Authorized tradeoff recorded as DECISION proposal",
                new GivenSpec(
                        "e08-title",
                        List.of(new GraphStep.CreateRootQuestion("e08-root-question", true)),
                        new UserEvent.AnswerTip("e08-authorized-tradeoff",
                                AgentEvent.PersistenceIntent.RECORD_DECISION_NODE),
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(),
                        List.of(),
                        new BrainScript(
                                List.of(new BrainClaim("constraint", "e08-decided", "confirmed", 0.9)),
                                new BrainDecision("CREATE_NODE", Map.of(
                                        "kind", "KNOWLEDGE",
                                        "subtype", "DECISION",
                                        "contentTextSeed", "e08-decision")),
                                List.of("e08-known"),
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
                        Set.of("INVOKE_CAPABILITY", "WAIT"),
                        Map.of("answers", 1, "patches", 1, "proposals", 1),
                        Map.of("capabilityInvocations", 0, "relations", 0),
                        CallBudget.normalAnswerCycle()),
                List.of(
                        VariantSpec.base("delegated", 801L),
                        VariantSpec.builder("delegated-paraphrase", 802L)
                                .paraphraseIndex(1)
                                .usage(VariantSpec.Usage.CALIBRATION)
                                .build()));
        scenario.validate();
        return scenario;
    }

    /**
     * E09——路由规划:本阶段路由创建提案没有可执行的运行时命令路径,
     * policy 直接拒绝——绝不产生"可点击但不可执行"的提案。
     * 回答循环本身照常持久化,运行以 policy_denied 结束。
     */
    public static ScenarioDefinition e09() {
        ScenarioDefinition scenario = new ScenarioDefinition(
                "E09",
                "1",
                "Route creation without runtime path is denied",
                new GivenSpec(
                        "e09-title",
                        List.of(new GraphStep.CreateRootQuestion("e09-root-question", true)),
                        new UserEvent.AnswerTip("e09-answer"),
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(),
                        List.of(),
                        new BrainScript(
                                List.of(new BrainClaim("goal", "e09-claim", "confirmed", 0.9)),
                                new BrainDecision("CREATE_ROUTE", Map.of(
                                        "labelSeed", "e09-route")),
                                List.of("e09-known"),
                                List.of())),
                new ExpectSpec(
                        Set.of("GRAPH_INTEGRITY", "SHARED_STATE_IDENTITY",
                                "ANSWER_IMMUTABILITY", "MUTATION_AT_MOST_ONCE",
                                "NO_UNEXPECTED_STATE_DELTA"),
                        List.of(
                                new PropertyCheck("ANSWER_PERSISTED", Map.of()),
                                new PropertyCheck("PATCH_PERSISTED", Map.of()),
                                new PropertyCheck("RUN_COMPLETED", Map.of())),
                        Set.of("CREATE_ROUTE"),
                        Set.of("INVOKE_CAPABILITY", "GENERATE_ARTIFACT"),
                        Map.of("answers", 1, "patches", 1, "proposals", 1, "nodes", 0),
                        Map.of("capabilityInvocations", 0, "relations", 0, "routes", 0),
                        CallBudget.normalAnswerCycle()),
                List.of(VariantSpec.base("planning", 901L)));
        scenario.validate();
        return scenario;
    }

    /**
     * E10——资源锚定:附件资源出现在上下文中,但回答循环照常完成——
     * 资源只提供信息,绝不劫持决策。
     */
    public static ScenarioDefinition e10() {
        ScenarioDefinition scenario = new ScenarioDefinition(
                "E10",
                "1",
                "Attached resource does not hijack the answer cycle",
                new GivenSpec(
                        "e10-title",
                        List.of(
                                new GraphStep.AttachResource("e10-background"),
                                new GraphStep.CreateChildQuestion(
                                        "step-0", "e10-root-question", true)),
                        new UserEvent.AnswerTip("e10-answer"),
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(),
                        List.of(),
                        new BrainScript(
                                List.of(new BrainClaim("goal", "e10-claim", "confirmed", 0.9)),
                                new BrainDecision("REQUEST_USER_INPUT", Map.of(
                                        "questionTextSeed", "e10-next-question",
                                        "options", List.of(Map.of("label", "Clarify")),
                                        "allowFreeAnswer", true)),
                                List.of("e10-known"),
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
                        VariantSpec.base("grounded", 1001L),
                        VariantSpec.builder("grounded-decoy", 1002L)
                                .decoyResourceText("e10-unrelated-background")
                                .usage(VariantSpec.Usage.HOLDOUT)
                                .build()));
        scenario.validate();
        return scenario;
    }

    /**
     * E11——能力成功:只读探针被调用并自动执行(无副作用类 NONE),
     * 恰好一次,运行正常完成。
     */
    public static ScenarioDefinition e11() {
        ScenarioDefinition scenario = new ScenarioDefinition(
                "E11",
                "1",
                "Read-only capability auto-executes exactly once",
                new GivenSpec(
                        "e11-title",
                        List.of(
                                new GraphStep.AttachResource("e11-resource"),
                                new GraphStep.CreateChildQuestion(
                                        "step-0", "e11-root-question", true)),
                        new UserEvent.AnswerTip("e11-answer"),
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(new CapabilitySpec("eval.decoy.read-only",
                                "NONE", true, Map.of())),
                        List.of(),
                        new BrainScript(
                                List.of(new BrainClaim("goal", "e11-claim", "confirmed", 0.9)),
                                new BrainDecision("INVOKE_CAPABILITY", Map.of(
                                        "capabilityId", "eval.decoy.read-only",
                                        "arguments", Map.of("nodeRef", "step:tip"))),
                                List.of("e11-known"),
                                List.of())),
                new ExpectSpec(
                        Set.of("GRAPH_INTEGRITY",
                                "UNAUTHORIZED_CAPABILITY_NOT_EXECUTED",
                                "NO_UNEXPECTED_STATE_DELTA"),
                        List.of(
                                new PropertyCheck("ANSWER_PERSISTED", Map.of()),
                                new PropertyCheck("PATCH_PERSISTED", Map.of()),
                                new PropertyCheck("RUN_COMPLETED", Map.of())),
                        Set.of("INVOKE_CAPABILITY"),
                        Set.of("WAIT"),
                        Map.of("answers", 1, "patches", 1),
                        Map.of("relations", 0),
                        CallBudget.normalAnswerCycle()),
                List.of(VariantSpec.base("read-only", 1101L)));
        scenario.validate();
        return scenario;
    }

    /**
     * E12——能力失败:探针报告失败,以类型化消息呈现——运行仍然完成,
     * 没有任何静默重试,回答循环之外不发生任何图变更。
     */
    public static ScenarioDefinition e12() {
        ScenarioDefinition scenario = new ScenarioDefinition(
                "E12",
                "1",
                "Failing capability surfaces typed failure without retry",
                new GivenSpec(
                        "e12-title",
                        List.of(
                                new GraphStep.AttachResource("e12-resource"),
                                new GraphStep.CreateChildQuestion(
                                        "step-0", "e12-root-question", true)),
                        new UserEvent.AnswerTip("e12-answer"),
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(new CapabilitySpec("eval.decoy.local-durable",
                                "LOCAL_DURABLE", false, Map.of())),
                        List.of(),
                        new BrainScript(
                                List.of(new BrainClaim("goal", "e12-claim", "confirmed", 0.9)),
                                new BrainDecision("INVOKE_CAPABILITY", Map.of(
                                        "capabilityId", "eval.decoy.read-only",
                                        "arguments", Map.of("nodeRef", "step:tip"))),
                                List.of("e12-known"),
                                List.of())),
                new ExpectSpec(
                        Set.of("GRAPH_INTEGRITY",
                                "UNAUTHORIZED_CAPABILITY_NOT_EXECUTED",
                                "NO_UNEXPECTED_STATE_DELTA"),
                        List.of(
                                new PropertyCheck("ANSWER_PERSISTED", Map.of()),
                                new PropertyCheck("PATCH_PERSISTED", Map.of()),
                                new PropertyCheck("RUN_COMPLETED", Map.of())),
                        Set.of("INVOKE_CAPABILITY"),
                        Set.of("WAIT"),
                        Map.of("answers", 1, "patches", 1),
                        Map.of("relations", 0),
                        CallBudget.normalAnswerCycle()),
                List.of(VariantSpec.base("failing", 1201L)));
        scenario.validate();
        return scenario;
    }

    /**
     * E13——无关能力:诱饵能力已注册,但决策不得调用其中任何一个——
     * 无副作用、无调用。
     */
    public static ScenarioDefinition e13() {
        ScenarioDefinition scenario = new ScenarioDefinition(
                "E13",
                "1",
                "Irrelevant capabilities are never invoked",
                new GivenSpec(
                        "e13-title",
                        List.of(new GraphStep.CreateRootQuestion("e13-root-question", true)),
                        new UserEvent.AnswerTip("e13-answer"),
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(),
                        List.of(),
                        new BrainScript(
                                List.of(new BrainClaim("goal", "e13-claim", "confirmed", 0.9)),
                                new BrainDecision("REQUEST_USER_INPUT", Map.of(
                                        "questionTextSeed", "e13-next-question",
                                        "options", List.of(Map.of("label", "Clarify")),
                                        "allowFreeAnswer", true)),
                                List.of("e13-known"),
                                List.of())),
                new ExpectSpec(
                        Set.of("GRAPH_INTEGRITY", "MUTATION_AT_MOST_ONCE",
                                "NO_UNEXPECTED_STATE_DELTA"),
                        List.of(
                                new PropertyCheck("ANSWER_PERSISTED", Map.of()),
                                new PropertyCheck("PATCH_PERSISTED", Map.of()),
                                new PropertyCheck("NO_SIDE_EFFECT",
                                        Map.of("maxCapabilityCalls", 0)),
                                new PropertyCheck("RUN_COMPLETED", Map.of())),
                        Set.of("REQUEST_USER_INPUT"),
                        Set.of("INVOKE_CAPABILITY"),
                        Map.of("answers", 1, "patches", 1, "nodes", 1),
                        Map.of("capabilityInvocations", 0, "relations", 0),
                        CallBudget.normalAnswerCycle()),
                List.of(
                        VariantSpec.base("irrelevant", 1301L),
                        VariantSpec.builder("irrelevant-decoy", 1302L)
                                .decoyCapability("eval.decoy.external")
                                .usage(VariantSpec.Usage.HOLDOUT)
                                .build()));
        scenario.validate();
        return scenario;
    }

    /**
     * E19——大上下文:多个资源与兄弟问题共存,回答循环仍以恰好一次变更完成——
     * 规模不能破坏循环。
     */
    public static ScenarioDefinition e19() {
        ScenarioDefinition scenario = new ScenarioDefinition(
                "E19",
                "1",
                "Large context still completes with one mutation",
                new GivenSpec(
                        "e19-title",
                        List.of(
                                new GraphStep.AttachResource("e19-background-a"),
                                new GraphStep.AttachResource("e19-background-b"),
                                new GraphStep.CreateChildQuestion(
                                        "step-1", "e19-root-question", true)),
                        new UserEvent.AnswerTip("e19-answer"),
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(),
                        List.of(),
                        new BrainScript(
                                List.of(new BrainClaim("goal", "e19-claim", "confirmed", 0.9)),
                                new BrainDecision("REQUEST_USER_INPUT", Map.of(
                                        "questionTextSeed", "e19-next-question",
                                        "options", List.of(Map.of("label", "Clarify")),
                                        "allowFreeAnswer", true)),
                                List.of("e19-known"),
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
                        VariantSpec.base("large", 1901L),
                        VariantSpec.builder("large-shuffled", 1902L)
                                .shuffleIrrelevantContext(true)
                                .usage(VariantSpec.Usage.HOLDOUT)
                                .build()));
        scenario.validate();
        return scenario;
    }

    /**
     * E22-wait——合法 WAIT:agent 暂停,不改动已持久化回答循环之外的任何图内容。
     * WAIT 作为只读动作自动执行。
     */
    public static ScenarioDefinition e22Wait() {
        ScenarioDefinition scenario = new ScenarioDefinition(
                "E22-wait",
                "1",
                "Legitimate WAIT pauses with no graph mutation",
                new GivenSpec(
                        "e22-title",
                        List.of(new GraphStep.CreateRootQuestion("e22-root-question", true)),
                        new UserEvent.AnswerTip("e22-answer"),
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(),
                        List.of(),
                        new BrainScript(
                                List.of(new BrainClaim("goal", "e22-claim", "confirmed", 0.9)),
                                new BrainDecision("WAIT", Map.of()),
                                List.of("e22-known"),
                                List.of())),
                new ExpectSpec(
                        Set.of("GRAPH_INTEGRITY", "SHARED_STATE_IDENTITY",
                                "ANSWER_IMMUTABILITY", "MUTATION_AT_MOST_ONCE",
                                "NO_UNEXPECTED_STATE_DELTA"),
                        List.of(
                                new PropertyCheck("ANSWER_PERSISTED", Map.of()),
                                new PropertyCheck("PATCH_PERSISTED", Map.of()),
                                new PropertyCheck("RUN_COMPLETED", Map.of())),
                        Set.of("WAIT"),
                        Set.of("INVOKE_CAPABILITY", "GENERATE_ARTIFACT"),
                        Map.of("answers", 1, "patches", 1, "nodes", 0),
                        Map.of("capabilityInvocations", 0, "relations", 0),
                        CallBudget.normalAnswerCycle()),
                List.of(VariantSpec.base("legitimate", 2201L)));
        scenario.validate();
        return scenario;
    }

    /**
     * E24——活动路由与 Focus:工作 focus 位于兄弟路由,而回答循环运行在活动
     * 路由上。Focus 永远不能决定 Answer 的归属——回答仍落在活动尖端上。
     */
    public static ScenarioDefinition e24() {
        ScenarioDefinition scenario = new ScenarioDefinition(
                "E24",
                "1",
                "Focus placement cannot change answer ownership",
                new GivenSpec(
                        "e24-title",
                        List.of(
                                new GraphStep.CreateRootQuestion("e24-root-question", true),
                                new GraphStep.ForkFromNode("step-0", "e24-fork"),
                                new GraphStep.CreateChildQuestion(
                                        "step-0", "e24-child-question", true)),
                        new UserEvent.AnswerTip("e24-answer"),
                        new RouteContextSpec(RouteContextSpec.Kind.ACTIVE_TIP, null),
                        new FocusContextSpec(null, null),
                        List.of(),
                        List.of(),
                        new BrainScript(
                                List.of(new BrainClaim("goal", "e24-claim", "confirmed", 0.9)),
                                new BrainDecision("REQUEST_USER_INPUT", Map.of(
                                        "questionTextSeed", "e24-next-question",
                                        "options", List.of(Map.of("label", "Clarify")),
                                        "allowFreeAnswer", true)),
                                List.of("e24-known"),
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
                        Map.of("answers", 1, "patches", 1, "nodes", 1, "routes", 0),
                        Map.of("capabilityInvocations", 0, "relations", 0),
                        CallBudget.normalAnswerCycle()),
                List.of(
                        VariantSpec.builder("focus-differs", 2401L)
                                .focusDiffersFromActive(true)
                                .build(),
                        VariantSpec.builder("focus-differs-paraphrase", 2402L)
                                .focusDiffersFromActive(true)
                                .paraphraseIndex(1)
                                .usage(VariantSpec.Usage.HOLDOUT)
                                .build()));
        scenario.validate();
        return scenario;
    }
}
