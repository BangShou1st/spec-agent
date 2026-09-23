package com.specagent.common;

/**
 * Marker base for state-conflict exceptions that carry a precise, stable
 * reason code.
 *
 * <p><b>Contract.</b> A {@code PreciseConflictException} must be surfaced by
 * the HTTP layer as its own precise {@code 409} code (for example
 * {@code NO_ACTIVE_ROUTE}, {@code ROUTE_NOT_OPEN},
 * {@code UNANSWERED_QUESTION_HAS_CHILD}, {@code RELATION_DEPENDENCY_CYCLE}).
 * It must never be degraded into the generic {@code RUNTIME_CONFLICT} body:
 * the whole point of the type is that the client can act on the specific rule
 * that was violated.
 *
 * <p><b>Why a base class.</b> Command endpoints funnel their exceptions
 * through {@link com.specagent.workspace.route.CommandExecution#execute}, whose
 * catch clauses are type-based. A catch on {@code IllegalStateException}
 * (the natural supertype of a state conflict) would silently swallow every
 * precise conflict thrown inside the action. This base class gives that
 * wrapper one stable, future-proof hook:
 *
 * <pre>
 * catch (PreciseConflictException ex) { throw ex; }   // precise 409 preserved
 * catch (IllegalStateException ex)    { ... }          // generic 409
 * </pre>
 *
 * <p><b>Rule for new exceptions.</b> Every new "precise conflict" exception —
 * a state precondition failure that must reach the client with its own code —
 * MUST extend this class (directly or indirectly) and MUST be mapped by
 * {@code ApiExceptionHandler} with its own reason code. An architecture test
 * enforces the naming/type convention so a future exception cannot silently
 * fall back to {@code RUNTIME_CONFLICT}.
 *
 * <p>Extending {@link IllegalStateException} keeps the historical contract for
 * callers and tests that classify these failures as state conflicts.
 */
public abstract class PreciseConflictException extends IllegalStateException {

    protected PreciseConflictException(String message) {
        super(message);
    }

    protected PreciseConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
