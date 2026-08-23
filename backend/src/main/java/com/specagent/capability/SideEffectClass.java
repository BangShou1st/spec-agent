package com.specagent.capability;

/**
 * Side-effect classification of a capability. The Policy Engine — never the
 * model — derives approval requirements from this runtime-owned class.
 */
public enum SideEffectClass {

    /** Read-only retrieval/computation; no durable change anywhere. */
    NONE,
    /** Durable change inside the local graph/workspace only. */
    LOCAL_DURABLE,
    /** External side effect that a separate provider action could reverse. */
    EXTERNAL_REVERSIBLE,
    /** External side effect that cannot be undone by graph undo. */
    EXTERNAL_IRREVERSIBLE;

    public String code() {
        return name();
    }

    public static SideEffectClass fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("Side effect class must not be null");
        }
        return valueOf(code.trim().toUpperCase());
    }
}
