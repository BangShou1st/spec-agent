package com.specagent.readmodel.requirement;

/**
 * Read-model-neutral failure raised by requirement-state query reads.
 *
 * <p>The read-model/application layer must not depend on the outer HTTP API
 * layer, so expected query failures are expressed with this closed reason
 * instead of an API exception. The API boundary translates the reason into the
 * stable HTTP contract (404 {@code PROJECT_NOT_FOUND}, 500
 * {@code INTERNAL_INVARIANT_VIOLATION}).
 *
 * <p>Messages are static and safe: they never carry secrets, raw persistence
 * data, or provider payloads, and the API boundary never echoes them to the
 * client.
 */
public class RequirementStateQueryException extends RuntimeException {

    /** Closed, strongly bounded failure reasons for requirement-state reads. */
    public enum Reason {
        /** The requested project does not exist. */
        PROJECT_NOT_FOUND,
        /** The active route pointer failed to resolve to a route owned by the project. */
        INVARIANT_VIOLATION
    }

    private final Reason reason;

    private RequirementStateQueryException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public static RequirementStateQueryException of(Reason reason, String message) {
        return new RequirementStateQueryException(reason, message);
    }

    public Reason reason() {
        return reason;
    }
}
