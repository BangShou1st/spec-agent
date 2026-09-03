package com.specagent.eval;

/** Which route the answer cycle runs on. */
public record RouteContextSpec(Kind kind, String routeStepRef) {

    public enum Kind {
        ACTIVE_TIP,
        FORK_TIP
    }

    public String canonical() {
        return "route(" + kind + "," + routeStepRef + ")";
    }
}
