package com.specagent.eval;

import java.util.ArrayList;
import java.util.List;

/**
 * One anti-overfit parametrization of a scenario.
 *
 * <p>A scenario is never bound to fixed UUIDs, fixed natural-language
 * sentences, fixed ordering, or fixed route names. Variants express the
 * perturbation axes (paraphrased wording via {@code paraphraseIndex},
 * shuffled irrelevant context, active/focus divergence, decoy capabilities
 * and resources, route membership variations) while the scenario semantics
 * stay fixed. {@code seed} keeps every variant reproducible.
 *
 * <p>Usage tags support the calibration/holdout discipline: calibration
 * variants may inform prompt tuning, holdout variants never do.
 */
public record VariantSpec(
        String variantId,
        long seed,
        int paraphraseIndex,
        boolean shuffleIrrelevantContext,
        boolean focusDiffersFromActive,
        List<String> decoyCapabilities,
        List<String> decoyResourceTexts,
        String routeVariation,
        Usage usage) {

    public enum Usage {
        GENERAL,
        CALIBRATION,
        HOLDOUT
    }

    public VariantSpec {
        decoyCapabilities = decoyCapabilities == null ? List.of() : List.copyOf(decoyCapabilities);
        decoyResourceTexts = decoyResourceTexts == null ? List.of() : List.copyOf(decoyResourceTexts);
    }

    public static VariantSpec base(String variantId, long seed) {
        return new VariantSpec(variantId, seed, 0, false, false,
                List.of(), List.of(), null, Usage.GENERAL);
    }

    public static Builder builder(String variantId, long seed) {
        return new Builder(variantId, seed);
    }

    public String canonicalString() {
        List<String> decoys = new ArrayList<>(decoyCapabilities);
        decoys.sort(String::compareTo);
        List<String> resources = new ArrayList<>(decoyResourceTexts);
        resources.sort(String::compareTo);
        return "variant(" + variantId + "," + seed + "," + paraphraseIndex + ","
                + shuffleIrrelevantContext + "," + focusDiffersFromActive + ","
                + decoys + "," + resources + "," + routeVariation + "," + usage + ")";
    }

    public static final class Builder {
        private final String variantId;
        private final long seed;
        private int paraphraseIndex;
        private boolean shuffleIrrelevantContext;
        private boolean focusDiffersFromActive;
        private final List<String> decoyCapabilities = new ArrayList<>();
        private final List<String> decoyResourceTexts = new ArrayList<>();
        private String routeVariation;
        private Usage usage = Usage.GENERAL;

        private Builder(String variantId, long seed) {
            this.variantId = variantId;
            this.seed = seed;
        }

        public Builder paraphraseIndex(int paraphraseIndex) {
            this.paraphraseIndex = paraphraseIndex;
            return this;
        }

        public Builder shuffleIrrelevantContext(boolean shuffle) {
            this.shuffleIrrelevantContext = shuffle;
            return this;
        }

        public Builder focusDiffersFromActive(boolean differs) {
            this.focusDiffersFromActive = differs;
            return this;
        }

        public Builder decoyCapability(String capabilityId) {
            this.decoyCapabilities.add(capabilityId);
            return this;
        }

        public Builder decoyResourceText(String text) {
            this.decoyResourceTexts.add(text);
            return this;
        }

        public Builder routeVariation(String routeVariation) {
            this.routeVariation = routeVariation;
            return this;
        }

        public Builder usage(Usage usage) {
            this.usage = usage;
            return this;
        }

        public VariantSpec build() {
            return new VariantSpec(variantId, seed, paraphraseIndex,
                    shuffleIrrelevantContext, focusDiffersFromActive,
                    List.copyOf(decoyCapabilities), List.copyOf(decoyResourceTexts),
                    routeVariation, usage);
        }
    }
}
