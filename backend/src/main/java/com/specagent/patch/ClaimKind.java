package com.specagent.patch;

/**
 * Generic kind of a requirement claim.
 *
 * <p>These kinds are domain-neutral requirement mechanics. They must never encode
 * concrete business domains (software features, marketing channels, ecommerce
 * products, course assignments, etc.).
 */
public enum ClaimKind {
    GOAL,
    STAKEHOLDER,
    SCOPE,
    CONSTRAINT,
    SUCCESS_CRITERION,
    OUTPUT_EXPECTATION,
    RISK,
    ASSUMPTION,
    OPEN_QUESTION,
    CONFLICT,
    OTHER;

    public String code() {
        return name().toLowerCase();
    }

    public static ClaimKind fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Claim kind code must not be null");
        }
        return ClaimKind.valueOf(code.toUpperCase());
    }
}
