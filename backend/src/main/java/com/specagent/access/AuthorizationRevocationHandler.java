package com.specagent.access;

/** Metadata-only convergence for executions which do not have a public runtime task. */
public interface AuthorizationRevocationHandler {
    void revoke(AuthorizationRevocationOutbox.Event event);
}
