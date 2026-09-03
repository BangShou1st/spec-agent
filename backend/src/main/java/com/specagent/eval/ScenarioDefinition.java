package com.specagent.eval;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * One declarative evaluation scenario with its variants.
 *
 * <p>The scenario id is stable (E01…E25); the hash covers the semantics that
 * affect evaluation (given + expect + variant contents), independent of
 * variant ordering. Calibration/holdout usage is tracked per variant so
 * future prompt tuning can exclude holdout variants.
 */
public record ScenarioDefinition(
        String scenarioId,
        String scenarioVersion,
        String description,
        GivenSpec given,
        ExpectSpec expect,
        List<VariantSpec> variants) {

    public ScenarioDefinition {
        variants = variants == null ? List.of() : List.copyOf(variants);
    }

    /** Validates structural rules fail-fast (unique variants, no action overlap). */
    public void validate() {
        if (variants.isEmpty()) {
            throw new IllegalArgumentException(
                    "Scenario " + scenarioId + " requires at least one variant");
        }
        List<String> ids = new ArrayList<>();
        for (VariantSpec variant : variants) {
            ids.add(variant.variantId());
        }
        if (ids.stream().distinct().count() != ids.size()) {
            throw new IllegalArgumentException(
                    "Scenario " + scenarioId + " has duplicate variant ids: " + ids);
        }
        for (String action : expect.acceptablePrimaryActions()) {
            if (expect.forbiddenActions().contains(action)) {
                throw new IllegalArgumentException(
                        "Scenario " + scenarioId + " lists action as both acceptable and forbidden: "
                                + action);
            }
        }
    }

    /** Stable hash over scenario semantics (given + expect + variant contents). */
    public String scenarioHash() {
        List<String> renderedVariants = new ArrayList<>();
        for (VariantSpec variant : variants) {
            renderedVariants.add(variant.canonicalString());
        }
        renderedVariants.sort(Comparator.naturalOrder());
        String canonical = "scenario[" + scenarioId + "," + scenarioVersion + ";"
                + given.canonical() + ";" + expect.canonical() + ";"
                + renderedVariants + "]";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 16);
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public List<String> calibrationVariants() {
        return variants.stream()
                .filter(variant -> variant.usage() == VariantSpec.Usage.CALIBRATION)
                .map(VariantSpec::variantId)
                .sorted()
                .toList();
    }

    public List<String> holdoutVariants() {
        return variants.stream()
                .filter(variant -> variant.usage() == VariantSpec.Usage.HOLDOUT)
                .map(VariantSpec::variantId)
                .sorted()
                .toList();
    }
}
