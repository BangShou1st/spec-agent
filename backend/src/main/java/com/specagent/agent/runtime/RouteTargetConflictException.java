package com.specagent.agent.runtime;

import com.specagent.common.PreciseConflictException;

/**
 * Thrown when a new run cannot target its route because the project's runtime
 * route state does not allow it: the project has no active route, or the
 * requested route is no longer OPEN. Carries a stable reason code so the API
 * layer can surface a precise 409 instead of a generic runtime conflict.
 *
 * <p>Extends {@link PreciseConflictException} (itself an
 * {@code IllegalStateException}): the conflict is a state precondition
 * failure, so existing callers/tests that classify the original
 * {code IllegalStateException("no active route")} keep matching, while the
 * API layer maps the concrete type to a precise 409 and
 * {@code CommandExecution} preserves it instead of degrading it to
 * {@code RUNTIME_CONFLICT}.
 */
public class RouteTargetConflictException extends PreciseConflictException {

    public enum Reason {
        /** The project has no active route pointer. */
        NO_ACTIVE_ROUTE,
        /** The requested route exists but its lifecycle is not OPEN. */
        ROUTE_NOT_OPEN
    }

    private final Reason reason;

    public RouteTargetConflictException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
