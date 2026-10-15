package com.specagent.capability;

/** Provider-owned sources must also be authorized when dispatch would only return a stored result. */
public interface RecordedResultAuthorization {
    void requireAuthorizedResult(CapabilityInvocationRecord result);
}
