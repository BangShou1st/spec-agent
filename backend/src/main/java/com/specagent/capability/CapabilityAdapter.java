package com.specagent.capability;

/**
 * Port implemented by every capability adapter. Adapters translate one
 * bounded capability contract into a concrete implementation (internal Java
 * tool, Skill package, MCP server, future provider) without leaking the
 * implementation into the planner or action protocol.
 */
public interface CapabilityAdapter {

    /** The bounded contract this adapter implements. */
    CapabilityDescriptor descriptor();

    /**
     * Executes the capability. Implementations must be deterministic given
     * the same arguments, must never mutate external systems unless the
     * descriptor's side-effect class declares it, and must return typed
     * results rather than throwing for expected failures.
     */
    CapabilityResult invoke(CapabilityInvocation invocation);
}
