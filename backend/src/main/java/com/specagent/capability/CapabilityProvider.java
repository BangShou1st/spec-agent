package com.specagent.capability;

import java.util.Collection;
import java.util.Optional;

/**
 * Dynamic capability provider SPI. A provider owns a family of capabilities
 * whose descriptors may change over time (e.g. MCP tools discovered from a
 * connection) without any planner-core change.
 *
 * <p>Providers never expose implementation classes, SDK clients, credentials,
 * or endpoints to the planner: {@link CapabilityDescriptor} is the only
 * model-visible contract. Availability is the provider's responsibility — a
 * provider returns a descriptor (or resolves it) only while the underlying
 * capability is actually available (e.g. connection enabled/connected), so
 * disabled resources disappear from planner candidates by construction.
 *
 * <p>One server may own many tools; one provider is <em>not</em> one
 * capability. Duplicate capability ids across providers (or against static
 * adapters) fail closed in the registry.
 */
public interface CapabilityProvider {

    /** Stable provider name for diagnostics/trace; never planner-facing. */
    String providerName();

    /**
     * Descriptors currently offered for the given query context. Providers
     * apply their own availability filtering (enabled/connected/configured);
     * permission filtering is applied by the registry on the result.
     */
    Collection<CapabilityDescriptor> descriptorsFor(CapabilityQueryContext context);

    /** True when this provider owns the id (ownership, not availability). */
    boolean canHandle(String capabilityId);

    /**
     * Descriptor for one id while it is currently available; empty when the
     * capability is disabled/disconnected/unknown. Policy uses this to resolve
     * side-effect class at invocation time.
     */
    Optional<CapabilityDescriptor> descriptorFor(String capabilityId);

    /**
     * Executes the capability. Callers must have passed policy for the
     * descriptor's side-effect class first; idempotency/retry is handled by
     * {@link CapabilityRuntime}, not by the provider.
     */
    CapabilityResult invoke(CapabilityInvocation invocation);
}