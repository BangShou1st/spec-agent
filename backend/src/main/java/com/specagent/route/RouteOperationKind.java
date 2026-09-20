package com.specagent.route;

/** Route command kinds recorded in the graph operation journal. */
public enum RouteOperationKind {
    ROUTE_LIFECYCLE,
    ROUTE_FORK,
    ROUTE_START,
    ROUTE_REANSWER,
    ROUTE_REGENERATE
}
