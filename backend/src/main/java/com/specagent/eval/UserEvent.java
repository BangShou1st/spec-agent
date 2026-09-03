package com.specagent.eval;

/**
 * The user event that triggers the production answer cycle of a scenario.
 */
public sealed interface UserEvent
        permits UserEvent.AnswerTip, UserEvent.DivergentAnswer {

    /** Answers the active-route tip with free text derived from the seed. */
    record AnswerTip(String freeTextSeed) implements UserEvent {
    }

    /**
     * Attempts a second answer on an already-answered canonical node from a
     * forked route (Shared State divergence probe; must fail closed).
     */
    record DivergentAnswer(String targetStepRef, String freeTextSeed) implements UserEvent {
    }

    /** Canonical rendering for the scenario hash. */
    static String canonical(UserEvent event) {
        if (event instanceof AnswerTip answer) {
            return "answer-tip(" + answer.freeTextSeed() + ")";
        }
        if (event instanceof DivergentAnswer divergent) {
            return "divergent(" + divergent.targetStepRef() + "," + divergent.freeTextSeed() + ")";
        }
        return "unknown";
    }
}
