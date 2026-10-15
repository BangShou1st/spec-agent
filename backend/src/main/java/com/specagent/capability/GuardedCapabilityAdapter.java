package com.specagent.capability;

/** Checks apply before claiming a business invocation row AND before returning recorded private results. */
public interface GuardedCapabilityAdapter extends CapabilityAdapter {
    void requireAuthorizedInvocation(CapabilityInvocation invocation);
}
