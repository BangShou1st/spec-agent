package com.specagent.route;

import com.specagent.node.Node;

/**
 * Deterministic result of a regenerate operation.
 *
 * <p>Carries the old route, the replacement route, and the replacement node.
 * The old route is marked superseded; the replacement route is open and active.
 *
 * <p>Historically this also carried a {@code ContextSnapshot}, but the
 * production replacement cycle never populated it (always {@code null}) and
 * never read it; the frozen regenerate context is built by the caller that
 * needs it (see {@code ReplacementCycleService}). The dead field was removed
 * to break the route -> context dependency edge.
 */
public class RegenerateResult {

    private final Route oldRoute;
    private final Route replacementRoute;
    private final Node replacementNode;

    public RegenerateResult(Route oldRoute,
                            Route replacementRoute,
                            Node replacementNode) {
        this.oldRoute = oldRoute;
        this.replacementRoute = replacementRoute;
        this.replacementNode = replacementNode;
    }

    public Route oldRoute() {
        return oldRoute;
    }

    public Route replacementRoute() {
        return replacementRoute;
    }

    public Node replacementNode() {
        return replacementNode;
    }
}
