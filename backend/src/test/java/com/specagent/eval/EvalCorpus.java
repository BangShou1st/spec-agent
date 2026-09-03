package com.specagent.eval;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * First deterministic corpus batch (P2): E01, E06, E07, E17, E25.
 *
 * <p>Every scenario is data, not bespoke logic: graph setup uses text
 * seeds (never fixed sentences), variants carry the anti-overfit axes,
 * and expectations assert action families, canonical state deltas, and
 * call budgets — never verbatim wording.
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
     * E01 — Simple Answer: the smoke scenario. One root question, one
     * answer, STATE_UPDATE persists a patch, DECISION asks the next
     * allowed question, nothing else mutates.
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
     * E06 — Shared State. Variant A: two routes share one canonical
     * Question/Answer identity (reads converge). Variant B: a forked
     * route attempts a divergent second answer on the same canonical
     * node and must fail closed without forking canonical state.
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
     * E07 — Conflict. Variant A: unresolved incompatible demands must
     * enter explicit resolution (REQUEST_USER_INPUT), never a silent
     * assumption. Variant B: the user already made the tradeoff, so the
     * agent must not re-ask the resolved question.
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
     * E07-resolved — the user already made the tradeoff. The agent must
     * not re-ask the resolved question; the conflict converges under
     * existing production semantics.
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
     * E17 — High-risk action. Variant A (unconfirmed): the proposal
     * waits for approval and no capability executes. Variant B is
     * exercised by the E17 test via acceptance of the pending proposal.
     * Variant C (stale confirmation) is exercised by advancing the
     * graph before acceptance and expecting fail-closed.
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
     * E25 — Frozen/stale context. The scripted decision is built against
     * the live snapshot, so staleness is probed at the acceptance
     * boundary: the E25 test advances the graph after the proposal goes
     * pending and expects acceptance to fail closed with no mutation.
     * The base attempt itself must complete cleanly with no unexpected
     * delta.
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
     * E25-stale — a confirmable agent-authored DECISION goes pending, then
     * the referenced context is retracted. Acceptance must fail closed
     * with no mutation: Brain output can never bypass Java validation.
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
