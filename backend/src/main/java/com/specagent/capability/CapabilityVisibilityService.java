package com.specagent.capability;

import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Deterministic capability eligibility for the planner-facing catalog.
 *
 * <p>{@code CapabilityVisibilityService} owns the <em>filtering</em> half of
 * the pipeline only: contextual compatibility (the descriptor's
 * {@code supports} facts against context node kinds), plus the bounded
 * projection (max visible count / payload bounds). It does not rank semantic
 * relevance, does not grant permission (permission filtering stays in the
 * registry), and does not decide the final semantic action — the model owns
 * that choice.
 *
 * <p>Retrieval/ranking for larger catalogs plugs in behind this service as a
 * separate {@code CapabilityCandidateRetriever} when scale evaluation
 * justifies it; deterministic eligibility and semantic relevance are never
 * fused into one god ranker.
 */
@Component
public class CapabilityVisibilityService {

    private final CapabilityRegistry registry;
    private final CapabilityCatalogLimits limits;

    public CapabilityVisibilityService(CapabilityRegistry registry,
                                       CapabilityCatalogLimits limits) {
        this.registry = registry;
        this.limits = limits;
    }

    /**
     * Permission-filtered, context-compatible, bounded capability descriptors.
     * Providers return only currently-available descriptors; the registry
     * applies permission filtering; this service applies {@code supports}
     * compatibility against {@code contextKinds} and the catalog bounds.
     */
    public List<CapabilityDescriptor> visibleCapabilities(CapabilityQueryContext context) {
        return registry.descriptorsFor(context).stream()
                .filter(descriptor -> supportsContext(descriptor, context.contextKinds()))
                .limit(limits.maxVisible())
                .map(this::boundDescriptor)
                .toList();
    }

    /**
     * Context-free visibility used by host tooling that lists the catalog
     * without a decision snapshot (e.g. management views).
     */
    public List<CapabilityDescriptor> visibleCapabilities() {
        return visibleCapabilities(CapabilityQueryContext.empty());
    }

    private boolean supportsContext(CapabilityDescriptor descriptor,
                                    List<String> contextKinds) {
        if (descriptor.supports().isEmpty()) {
            return true;
        }
        return descriptor.supports().stream().anyMatch(support -> contextKinds.stream()
                .anyMatch(kind -> supportMatches(support, kind)));
    }

    private boolean supportMatches(String support, String contextKind) {
        int separator = support.indexOf(':');
        if (separator < 0) {
            return support.equalsIgnoreCase(contextKind);
        }
        String supportKind = support.substring(0, separator);
        String supportSubtype = support.substring(separator + 1);
        int kindSeparator = contextKind.indexOf(':');
        if (kindSeparator < 0) {
            return supportKind.equalsIgnoreCase(contextKind);
        }
        return supportKind.equalsIgnoreCase(contextKind.substring(0, kindSeparator))
                && supportSubtype.equalsIgnoreCase(contextKind.substring(kindSeparator + 1));
    }

    /** Bounded model-facing metadata copy; oversized descriptions truncate. */
    private CapabilityDescriptor boundDescriptor(CapabilityDescriptor descriptor) {
        String description = descriptor.description();
        if (description.length() > limits.maxDescriptionChars()) {
            description = description.substring(0, limits.maxDescriptionChars()) + "…";
        }
        return new CapabilityDescriptor(
                descriptor.capabilityId(),
                descriptor.version(),
                description,
                descriptor.inputSchema(),
                descriptor.outputSchema(),
                descriptor.readOnly(),
                descriptor.sideEffectClass(),
                descriptor.requiredPermissions(),
                descriptor.supports());
    }
}