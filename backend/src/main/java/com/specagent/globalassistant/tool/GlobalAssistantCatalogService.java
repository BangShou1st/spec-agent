package com.specagent.globalassistant.tool;
import com.specagent.capability.CapabilityCatalogLimits;
import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityQueryContext;
import com.specagent.capability.CapabilityRegistry;
import java.util.List;
import org.springframework.stereotype.Service;
/**
 * Model-facing GA product catalog. Resolution order is deliberate:
 * registry permission/provider resolution first, then the GA allowed-ID
 * filter, then the exact GA support-marker compatibility check, and only
 * then the bounded projection. Unrelated capabilities can never starve the
 * four V1 tools out of a global truncation window. No second registry.
 */
@Service
public class GlobalAssistantCatalogService {
    private final CapabilityRegistry registry;
    private final CapabilityCatalogLimits limits;
    public GlobalAssistantCatalogService(CapabilityRegistry registry, CapabilityCatalogLimits limits) {
        this.registry = registry;
        this.limits = limits;
    }
    public List<CapabilityDescriptor> modelCatalog() {
        return registry.descriptorsFor(CapabilityQueryContext.empty()).stream()
                .filter(descriptor -> GlobalAssistantToolCatalog.isAllowed(descriptor.capabilityId()))
                .filter(descriptor -> descriptor.supports().contains(GlobalAssistantToolCatalog.SUPPORT_MARKER))
                .map(this::bound)
                .limit(8)
                .toList();
    }
    private CapabilityDescriptor bound(CapabilityDescriptor descriptor) {
        String description = descriptor.description();
        if (description != null && description.length() > limits.maxDescriptionChars()) {
            description = description.substring(0, limits.maxDescriptionChars()) + "...";
        }
        return new CapabilityDescriptor(descriptor.capabilityId(), descriptor.version(), description,
                descriptor.inputSchema(), descriptor.outputSchema(), descriptor.readOnly(),
                descriptor.sideEffectClass(), descriptor.requiredPermissions(), descriptor.supports());
    }
}
