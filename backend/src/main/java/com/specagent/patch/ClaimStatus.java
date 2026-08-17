package com.specagent.patch;

/**
 * Grounding status of a requirement claim.
 *
 * <p>Unsupported model output must be labeled {@code ASSUMED}, {@code UNRESOLVED},
 * or {@code REJECTED}, never {@code CONFIRMED} without a source reference.
 */
public enum ClaimStatus {
    CONFIRMED,
    ASSUMED,
    UNRESOLVED,
    REJECTED;

    public String code() {
        return name().toLowerCase();
    }

    public static ClaimStatus fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Claim status code must not be null");
        }
        return ClaimStatus.valueOf(code.toUpperCase());
    }
}
