package com.specagent.capability;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Owns capability discovery, permission filtering, and adapter/provider
 * routing for the host runtime.
 *
 * <p>The registry is the single host-side directory for two capability kinds
 * that coexist deliberately:
 *
 * <ul>
 *   <li><b>Static adapters</b> — legacy {@link CapabilityAdapter} instances
 *       (Host Function Tools and internal capabilities), registered once at
 *       construction; and</li>
 *   <li><b>Dynamic providers</b> — {@link CapabilityProvider} SPI instances
 *       whose descriptor sets may change at runtime (MCP tool discovery),
 *       queried per context.</li>
 * </ul>
 *
 * <p>Descriptors are filtered by granted permissions before model exposure —
 * the planner only ever sees capabilities the project/user may call, and it
 * never branches on implementation class names. Duplicate capability ids fail
 * closed across both sources: two owners of one id would be ambiguous.
 */
@Component
public class CapabilityRegistry {

    private final Map<String, CapabilityAdapter> adaptersById = new LinkedHashMap<>();
    private final List<CapabilityProvider> providers;

    /** Legacy constructor: static adapters only (tests and simple wiring). */
    public CapabilityRegistry(List<CapabilityAdapter> adapters) {
        this(adapters, List.of());
    }

    @Autowired
    public CapabilityRegistry(List<CapabilityAdapter> adapters,
                              List<CapabilityProvider> providers) {
        for (CapabilityAdapter adapter : adapters == null ? List.<CapabilityAdapter>of() : adapters) {
            String id = adapter.descriptor().capabilityId();
            if (adaptersById.put(id, adapter) != null) {
                throw new IllegalStateException("Duplicate capability id: " + id);
            }
        }
        this.providers = providers == null ? List.of() : List.copyOf(providers);
    }

    /** All static adapter descriptors (host-side view, unfiltered). */
    public Collection<CapabilityDescriptor> allDescriptors() {
        return adaptersById.values().stream().map(CapabilityAdapter::descriptor).toList();
    }

    /**
     * Descriptors the planner may see for an empty context: permission-filtered
     * static + dynamic descriptors. Kept for legacy call sites; prefer
     * {@link #descriptorsFor(CapabilityQueryContext)} for context-aware views.
     */
    public List<CapabilityDescriptor> descriptorsFor(Set<String> grantedPermissions) {
        return descriptorsFor(CapabilityQueryContext.forPermissions(grantedPermissions));
    }

    /**
     * Context-aware descriptors: static adapters plus every provider's
     * currently-offered descriptors, permission-filtered, deduplicated by id,
     * with duplicates failing closed. Providers apply their own availability
     * filtering (enabled/connected) before returning descriptors.
     */
    public List<CapabilityDescriptor> descriptorsFor(CapabilityQueryContext context) {
        Set<String> grants = context.grantedPermissions();
        Map<String, CapabilityDescriptor> byId = new LinkedHashMap<>();
        for (CapabilityAdapter adapter : adaptersById.values()) {
            CapabilityDescriptor descriptor = adapter.descriptor();
            if (grants.containsAll(descriptor.requiredPermissions())) {
                putUnique(byId, descriptor);
            }
        }
        for (CapabilityProvider provider : providers) {
            for (CapabilityDescriptor descriptor : provider.descriptorsFor(context)) {
                if (grants.containsAll(descriptor.requiredPermissions())) {
                    putUnique(byId, descriptor);
                }
            }
        }
        return List.copyOf(byId.values());
    }

    private void putUnique(Map<String, CapabilityDescriptor> byId,
                           CapabilityDescriptor descriptor) {
        String id = descriptor.capabilityId();
        if (byId.put(id, descriptor) != null) {
            throw new IllegalStateException(
                    "Duplicate capability id across providers/adapters: " + id);
        }
    }

    public Optional<CapabilityDescriptor> findDescriptor(String capabilityId) {
        CapabilityAdapter adapter = adaptersById.get(capabilityId);
        if (adapter != null) {
            return Optional.of(adapter.descriptor());
        }
        for (CapabilityProvider provider : providers) {
            if (provider.canHandle(capabilityId)) {
                Optional<CapabilityDescriptor> descriptor = provider.descriptorFor(capabilityId);
                if (descriptor.isPresent()) {
                    return descriptor;
                }
            }
        }
        return Optional.empty();
    }

    public Optional<CapabilityAdapter> findAdapter(String capabilityId) {
        CapabilityAdapter adapter = adaptersById.get(capabilityId);
        if (adapter != null) {
            return Optional.of(adapter);
        }
        for (CapabilityProvider provider : providers) {
            if (provider.canHandle(capabilityId)
                    && provider.descriptorFor(capabilityId).isPresent()) {
                return Optional.of(new ProviderBackedAdapter(provider, capabilityId));
            }
        }
        return Optional.empty();
    }

    /**
     * Bridges one provider-owned capability to the legacy adapter contract so
     * execution/dispatch code stays single-path. The descriptor is resolved
     * lazily per lookup so availability changes are honored.
     */
    private record ProviderBackedAdapter(CapabilityProvider provider, String capabilityId)
            implements CapabilityAdapter {

        @Override
        public CapabilityDescriptor descriptor() {
            return provider.descriptorFor(capabilityId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Provider capability became unavailable during dispatch: "
                                    + capabilityId));
        }

        @Override
        public CapabilityResult invoke(CapabilityInvocation invocation) {
            return provider.invoke(invocation);
        }
    }
}