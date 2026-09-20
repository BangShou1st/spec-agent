package com.specagent.model.provider;

/**
 * Semantics needed by the provider compatibility probe: decide whether a raw
 * completion is a legal FINAL decision.
 *
 * <p>The probe protocol (what a "legal decision" is) belongs to the assistant
 * domain, but the probe itself runs inside the provider layer. Depending on
 * this port instead of the concrete parser/validator breaks the
 * model &lt;-&gt; globalassistant package cycle; the adapter lives on the
 * assistant side and only forwards parse/validate/kind checks.
 */
public interface CompatibilityDecisionSemantics {

    /** Parses the raw completion into an opaque decision value; throws if unparsable. */
    Object parseDecision(String content) throws Exception;

    /** Authoritative validation of a decision produced by {@link #parseDecision}. */
    void validateDecision(Object decision) throws Exception;

    /** True when the decision carries the FINAL kind required by the probe. */
    boolean isFinal(Object decision);
}
