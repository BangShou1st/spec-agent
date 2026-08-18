package com.specagent.readmodel.route;

/**
 * Read-model-neutral failure raised by route-lineage reads.
 *
 * <p>The read-model/application layer must not depend on the outer HTTP API
 * layer, so expected query failures are expressed with this closed reason
 * instead of an API exception. The API boundary translates the reason into the
 * stable HTTP contract (404 {@code PROJECT_NOT_FOUND}, 404
 * {@code ROUTE_NOT_FOUND}, 500 {@code INTERNAL_INVARIANT_VIOLATION}).
 *
 * <p>Messages are static and safe: they never carry secrets, raw persistence
 * data, or provider payloads, and the API boundary never echoes them to the
 * client.
 */
public class RouteLineageQueryException extends RuntimeException {

    /** Closed, strongly bounded failure reasons for route-lineage reads. */
    public enum Reason {
        /** The requested project does not exist. */
        PROJECT_NOT_FOUND,
        /** The requested route does not exist or does not belong to the project. */
        ROUTE_NOT_FOUND,
        /** The route lineage failed an integrity/ownership invariant check. */
        INVARIANT_VIOLATION
    }

    private final Reason reason;

    private RouteLineageQueryException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public static RouteLineageQueryException of(Reason reason, String message) {
        return new RouteLineageQueryException(reason, message);
    }

    public Reason reason() {
        return reason;
    }
}
