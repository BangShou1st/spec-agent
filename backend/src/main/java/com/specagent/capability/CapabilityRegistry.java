package com.specagent.capability;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Owns capability discovery, permission filtering, and adapter routing.
 *
 * <p>Descriptors are filtered by granted permissions before model exposure
 * — the planner only ever sees capabilities the project/user may call, and
 * it never branches on implementation class names.
 */
@Component
public class CapabilityRegistry {

    private final Map<String, CapabilityAdapter> adaptersById = new LinkedHashMap<>();

    public CapabilityRegistry(List<CapabilityAdapter> adapters) {
        for (CapabilityAdapter adapter : adapters) {
            String id = adapter.descriptor().capabilityId();
            if (adaptersById.put(id, adapter) != null) {
                throw new IllegalStateException("Duplicate capability id: " + id);
            }
        }
    }

    /** All registered descriptors (host-side view, unfiltered). */
    public Collection<CapabilityDescriptor> allDescriptors() {
        return adaptersById.values().stream().map(CapabilityAdapter::descriptor).toList();
    }

    /**
     * Descriptors the planner may see: permission-filtered. Capabilities
     * whose required permissions are not granted are invisible, not merely
     * blocked — irrelevant capabilities are never called.
     */
    public List<CapabilityDescriptor> descriptorsFor(Set<String> grantedPermissions) {
        return allDescriptors().stream()
                .filter(descriptor -> grantedPermissions.containsAll(descriptor.requiredPermissions()))
                .toList();
    }

    public Optional<CapabilityDescriptor> findDescriptor(String capabilityId) {
        return Optional.ofNullable(adaptersById.get(capabilityId))
                .map(CapabilityAdapter::descriptor);
    }

    public Optional<CapabilityAdapter> findAdapter(String capabilityId) {
        return Optional.ofNullable(adaptersById.get(capabilityId));
    }
}
