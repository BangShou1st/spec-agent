package com.specagent.eval;

import com.specagent.agent.contract.AgentEvent;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Second corpus batch (P2 Phase 2): E02, E05, E08, E10, E11, E12, E13, E19,
 * E22-wait, E24.
 *
 * <p>Same contract as the first batch: every scenario is data (text seeds,
 * never fixed sentences), variants carry the anti-overfit axes, and
 * expectations assert action families, canonical state deltas, and call
 * budgets — never verbatim wording. Every scenario here first passes B-fast
 * (scripted) before entering the live baseline corpus, so a live red always
 * means live behavior — never a malformed scenario.
 *
 * <p>Deferred to a later round (need new UserEvent kinds or failure-semantic
 * proof): E03 answer revise (CONTINUE), E04 non-tip continue (new route
 * context), E09 route planning (CREATE_ROUTE deny shape), E22-improper WAIT
 * under conflict, E23 malformed provider output.
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
     * E02 — Ambiguity clarification: a vague answer still moves through the
     * full cycle into an explicit clarifying question, never a silent
     * assumption. Same cycle shape as E01 with ambiguity-flavored seeds.
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
     * E05 — Sibling isolation: answering on the active route after a fork
     * leaves the sibling route unpolluted — exactly one canonical answer
     * exists project-wide, and no new route is created by the answer cycle.
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
     * E08 — Conflict delegation: the user explicitly authorized the tradeoff
     * in this answer, so the agent records a KNOWLEDGE/DECISION node. A
     * model-authored DECISION is confirmed intent and always requires
     * confirmation — the attempt ends awaiting approval with no side effect.
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
     * E09 — Route planning: a route-creation proposal has no executable
     * runtime command path in this stage, so policy denies it outright —
     * never a clickable-but-unexecutable proposal. The answer cycle itself
     * still persists, and the run completes as policy-denied.
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
     * E10 — Resource grounding: an attached resource is present in context
     * but the answer cycle still completes normally — resources inform, they
     * never hijack the decision.
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
     * E11 — Capability success: a read-only probe is invoked and auto-executes
     * (NONE side-effect class), exactly once, with the run completing.
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
     * E12 — Capability failure: the probe reports failure, which surfaces as
     * a typed message — the run still completes, nothing retries silently,
     * and no graph mutation beyond the answer cycle occurs.
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
     * E13 — Irrelevant capability: decoy capabilities are registered but the
     * decision must not call any of them — no side effect, no invocation.
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
     * E19 — Large context: several resources and sibling questions coexist,
     * yet the answer cycle still completes with exactly one mutation — scale
     * must not break the cycle.
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
     * E22-wait — Legitimate WAIT: the agent pauses without mutating the graph
     * beyond the persisted answer cycle. WAIT auto-executes as read-only.
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
     * E24 — Active vs Focus: working focus sits on the sibling route while
     * the answer cycle runs on the active route. Focus never selects an
     * Answer — the answer still lands on the active tip.
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
