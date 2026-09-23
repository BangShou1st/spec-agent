package com.specagent.eval;

import java.util.List;
import java.util.Map;

/**
 * Declarative graph-setup steps of a scenario's {@code given} block.
 *
 * <p>Steps use stable seeds (questionSeed, answerSeed, ...) instead of
 * fixed UUIDs or fixed natural-language sentences: the runner derives
 * concrete texts deterministically from the scenario id, variant seed, and
 * paraphrase index, so variants never rebind the scenario to one exact
 * sentence.
 */
public sealed interface GraphStep
        permits GraphStep.CreateRootQuestion,
                GraphStep.CreateChildQuestion,
                GraphStep.AttachResource,
                GraphStep.ForkFromNode,
                GraphStep.SetFocus,
                GraphStep.SetKnowledgeStatus {

    /** Creates the root INTERACTION/QUESTION on the active route. */
    record CreateRootQuestion(String questionSeed, boolean allowFreeAnswer) implements GraphStep {
    }

    /** Creates a child INTERACTION/QUESTION under the node built by an earlier step. */
    record CreateChildQuestion(String parentStepRef, String questionSeed,
                               boolean allowFreeAnswer) implements GraphStep {
    }

    /** Attaches a TEXT resource at the current tip (or on an empty route). */
    record AttachResource(String textSeed) implements GraphStep {
    }

    /** Forks a new route from the node built by an earlier step. */
    record ForkFromNode(String sourceStepRef, String labelSeed) implements GraphStep {
    }

    /** Moves working focus without changing the active route. */
    record SetFocus(String targetStepRef) implements GraphStep {
    }

    /** Applies an explicit knowledge-state transition to a workspace node. */
    record SetKnowledgeStatus(String targetStepRef, String status) implements GraphStep {
    }

    /** Canonical rendering for the scenario hash. */
    static String canonical(List<GraphStep> steps) {
        StringBuilder rendered = new StringBuilder("[");
        for (GraphStep step : steps) {
            if (step instanceof CreateRootQuestion root) {
                rendered.append("root(").append(root.questionSeed())
                        .append(",").append(root.allowFreeAnswer()).append(");");
            } else if (step instanceof CreateChildQuestion child) {
                rendered.append("child(").append(child.parentStepRef())
                        .append(",").append(child.questionSeed())
                        .append(",").append(child.allowFreeAnswer()).append(");");
            } else if (step instanceof AttachResource resource) {
                rendered.append("resource(").append(resource.textSeed()).append(");");
            } else if (step instanceof ForkFromNode fork) {
                rendered.append("fork(").append(fork.sourceStepRef())
                        .append(",").append(fork.labelSeed()).append(");");
            } else if (step instanceof SetFocus focus) {
                rendered.append("focus(").append(focus.targetStepRef()).append(");");
            } else if (step instanceof SetKnowledgeStatus status) {
                rendered.append("status(").append(status.targetStepRef())
                        .append(",").append(status.status()).append(");");
            } else {
                rendered.append("unknown;");
            }
        }
        return rendered.append("]").toString();
    }

    /** Placeholder rendering for user-visible text seeds (never asserted verbatim). */
    static String renderText(String scenarioId, String seed, int paraphraseIndex,
                             Map<String, List<String>> paraphrases) {
        List<String> options = paraphrases.get(seed);
        if (options != null && !options.isEmpty()) {
            return options.get(Math.floorMod(paraphraseIndex, options.size()));
        }
        return "[" + scenarioId + ":" + seed + "#p" + paraphraseIndex + "]";
    }
}
