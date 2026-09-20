package com.specagent.graph;

import com.specagent.common.PreciseConflictException;

/**
 * Thrown when a graph mutation violates a topology/state rule the user can
 * act on (dependency cycle, detach/connect at a non-tip node, ...). Carries a
 * stable reason code so the API layer surfaces a precise 409 with product
 * copy instead of a generic runtime conflict.
 *
 * <p>Extends {@link PreciseConflictException} (itself an
 * {@code IllegalStateException}): a rule violation is a state precondition
 * failure, matching the historical
 * {@code IllegalStateException("<CODE>: ...")} contract the graph validators
 * documented, while the API layer maps the concrete type to a precise 409 and
 * {@code CommandExecution} preserves it instead of degrading it to
 * {@code RUNTIME_CONFLICT}.
 */
public class GraphRuleViolationException extends PreciseConflictException {

    private final String code;

    public GraphRuleViolationException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
