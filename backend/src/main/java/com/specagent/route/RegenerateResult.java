package com.specagent.route;

import com.specagent.context.ContextSnapshot;
import com.specagent.node.Node;

/**
 * Deterministic result of a regenerate operation.
 *
 * <p>Carries the old route, the replacement route, the replacement node, and the
 * regenerate context snapshot. The old route is marked superseded; the replacement
 * route is open and active.
 */
public class RegenerateResult {

    private final Route oldRoute;
    private final Route replacementRoute;
    private final Node replacementNode;
    private final ContextSnapshot contextSnapshot;

    public RegenerateResult(Route oldRoute,
                            Route replacementRoute,
                            Node replacementNode,
                            ContextSnapshot contextSnapshot) {
        this.oldRoute = oldRoute;
        this.replacementRoute = replacementRoute;
        this.replacementNode = replacementNode;
        this.contextSnapshot = contextSnapshot;
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

    public ContextSnapshot contextSnapshot() {
        return contextSnapshot;
    }
}
