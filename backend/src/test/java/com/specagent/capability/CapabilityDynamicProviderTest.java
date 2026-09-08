package com.specagent.capability;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Dynamic provider ownership rules: static adapters and dynamic providers
 * coexist, duplicate ids fail closed across sources, permissions filter
 * provider descriptors, and provider availability controls visibility.
 */
class CapabilityDynamicProviderTest {

    @Test
    void dynamicProviderDescriptorsMergeWithStaticAdapters() {
        CapabilityRegistry registry = new CapabilityRegistry(
                List.of(stubAdapter("host.open")),
                List.of(new FixedProvider("fixed.dynamic",
                        descriptor("fixed.dynamic", List.of(), Set.of()))));

        assertThat(registry.descriptorsFor(CapabilityQueryContext.empty()))
                .extracting(CapabilityDescriptor::capabilityId)
                .containsExactly("host.open", "fixed.dynamic");
    }

    @Test
    void providerDescriptorsArePermissionFiltered() {
        CapabilityRegistry registry = new CapabilityRegistry(
                List.of(),
                List.of(new FixedProvider("secure.tool",
                        descriptor("secure.tool", List.of("mcp:external"), Set.of()))));

        assertThat(registry.descriptorsFor(CapabilityQueryContext.empty()))
                .isEmpty();
        assertThat(registry.descriptorsFor(CapabilityQueryContext.forPermissions(
                Set.of("mcp:external"))))
                .extracting(CapabilityDescriptor::capabilityId)
                .containsExactly("secure.tool");
    }

    @Test
    void duplicateIdAcrossProviderAndAdapterFailsClosed() {
        CapabilityRegistry registry = new CapabilityRegistry(
                List.of(stubAdapter("dup.id")),
                List.of(new FixedProvider("dup.id",
                        descriptor("dup.id", List.of(), Set.of()))));
        // Providers are dynamic: the duplicate-id gate runs when the catalog
        // is built for a query, not at construction time.
        assertThatThrownBy(() -> registry.descriptorsFor(CapabilityQueryContext.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate capability id");
    }

    @Test
    void duplicateIdAcrossTwoProvidersFailsClosed() {
        CapabilityRegistry registry = new CapabilityRegistry(
                List.of(),
                List.of(
                        new FixedProvider("dup.id", descriptor("dup.id", List.of(), Set.of())),
                        new FixedProvider("dup.id", descriptor("dup.id", List.of(), Set.of()))));
        assertThatThrownBy(() -> registry.descriptorsFor(CapabilityQueryContext.empty()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate capability id");
    }

    @Test
    void unavailableProviderDescriptorDisappearsFromCatalog() {
        ToggleProvider provider = new ToggleProvider("toggle.tool", true);
        CapabilityRegistry registry = new CapabilityRegistry(List.of(), List.of(provider));

        assertThat(registry.descriptorsFor(CapabilityQueryContext.empty()))
                .extracting(CapabilityDescriptor::capabilityId)
                .containsExactly("toggle.tool");

        provider.setAvailable(false);

        assertThat(registry.descriptorsFor(CapabilityQueryContext.empty())).isEmpty();
        assertThat(registry.findDescriptor("toggle.tool")).isEmpty();
        assertThat(registry.findAdapter("toggle.tool")).isEmpty();
    }

    @Test
    void unavailableProviderHidesCapabilitiesFromPlannerCandidates() {
        ToggleProvider provider = new ToggleProvider("dynamic.read", true);
        CapabilityRegistry registry = new CapabilityRegistry(List.of(), List.of(provider));
        CapabilityVisibilityService visibility =
                new CapabilityVisibilityService(registry, CapabilityCatalogLimits.defaults());

        assertThat(visibility.visibleCapabilities(CapabilityQueryContext.empty()))
                .extracting(CapabilityDescriptor::capabilityId)
                .containsExactly("dynamic.read");

        provider.setAvailable(false);

        assertThat(visibility.visibleCapabilities(CapabilityQueryContext.empty())).isEmpty();
    }

    @Test
    void providerBackedAdapterInvokesThroughProvider() {
        CountingProvider provider = new CountingProvider("count.tool");
        CapabilityRegistry registry = new CapabilityRegistry(List.of(), List.of(provider));

        CapabilityAdapter adapter = registry.findAdapter("count.tool").orElseThrow();
        CapabilityResult result = adapter.invoke(new CapabilityInvocation(
                java.util.UUID.randomUUID(), "key-1", "count.tool",
                java.util.UUID.randomUUID(), null, Map.of()));

        assertThat(result.status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
        assertThat(provider.calls()).isEqualTo(1);
        assertThat(result.content()).containsEntry("provider", "count.tool");
    }

    @Test
    void visibilityServiceFiltersBySupportsCompatibility() {
        CapabilityRegistry registry = new CapabilityRegistry(
                List.of(stubAdapter("context.free"),
                        stubAdapter("resource.tool")),
                List.of());
        // Give resource.tool a supports declaration.
        registry = new CapabilityRegistry(
                List.of(
                        stubAdapter("context.free"),
                        stubAdapterWithSupports("resource.tool", List.of("RESOURCE:FILE", "RESOURCE:URL"))),
                List.of());
        CapabilityVisibilityService visibility =
                new CapabilityVisibilityService(registry, CapabilityCatalogLimits.defaults());

        // Empty context: context-free capability visible, resource-bound one hidden.
        assertThat(visibility.visibleCapabilities(CapabilityQueryContext.empty()))
                .extracting(CapabilityDescriptor::capabilityId)
                .containsExactly("context.free");

        // A RESOURCE:FILE context node makes the resource capability visible.
        assertThat(visibility.visibleCapabilities(new CapabilityQueryContext(
                Set.of(), List.of("RESOURCE", "RESOURCE:FILE"), Map.of())))
                .extracting(CapabilityDescriptor::capabilityId)
                .contains("resource.tool");
    }

    @Test
    void visibilityServiceBoundsCatalogSize() {
        CapabilityRegistry registry = new CapabilityRegistry(
                List.of(
                        stubAdapter("cap.1"), stubAdapter("cap.2"), stubAdapter("cap.3")),
                List.of());
        CapabilityCatalogLimits limits = new CapabilityCatalogLimits();
        limits.setMaxVisible(2);
        CapabilityVisibilityService visibility =
                new CapabilityVisibilityService(registry, limits);

        assertThat(visibility.visibleCapabilities(CapabilityQueryContext.empty()))
                .extracting(CapabilityDescriptor::capabilityId)
                .hasSize(2);
    }

    private CapabilityDescriptor descriptor(String id, List<String> permissions,
                                            Set<String> supports) {
        return new CapabilityDescriptor(id, "1", "dynamic " + id,
                Map.of("type", "object"), Map.of(), true, SideEffectClass.NONE,
                permissions, supports.stream().toList());
    }

    private CapabilityAdapter stubAdapter(String id) {
        return new CapabilityAdapter() {
            @Override
            public CapabilityDescriptor descriptor() {
                return new CapabilityDescriptor(id, "1", "stub " + id,
                        null, null, true, SideEffectClass.NONE, List.of(), List.of());
            }

            @Override
            public CapabilityResult invoke(CapabilityInvocation invocation) {
                return CapabilityResult.failed(invocation.invocationId(),
                        invocation.invocationKey(), id, "unused stub");
            }
        };
    }

    private CapabilityAdapter stubAdapterWithSupports(String id, List<String> supports) {
        return new CapabilityAdapter() {
            @Override
            public CapabilityDescriptor descriptor() {
                return new CapabilityDescriptor(id, "1", "stub " + id,
                        null, null, true, SideEffectClass.NONE, List.of(), supports);
            }

            @Override
            public CapabilityResult invoke(CapabilityInvocation invocation) {
                return CapabilityResult.failed(invocation.invocationId(),
                        invocation.invocationKey(), id, "unused stub");
            }
        };
    }

    /** Provider with a fixed descriptor set. */
    private static final class FixedProvider implements CapabilityProvider {
        private final String id;
        private final CapabilityDescriptor descriptor;

        FixedProvider(String id, CapabilityDescriptor descriptor) {
            this.id = id;
            this.descriptor = descriptor;
        }

        @Override
        public String providerName() {
            return "fixed";
        }

        @Override
        public Collection<CapabilityDescriptor> descriptorsFor(CapabilityQueryContext context) {
            return List.of(descriptor);
        }

        @Override
        public boolean canHandle(String capabilityId) {
            return id.equals(capabilityId);
        }

        @Override
        public Optional<CapabilityDescriptor> descriptorFor(String capabilityId) {
            return id.equals(capabilityId) ? Optional.of(descriptor) : Optional.empty();
        }

        @Override
        public CapabilityResult invoke(CapabilityInvocation invocation) {
            return new CapabilityResult(invocation.invocationId(), invocation.invocationKey(),
                    id, CapabilityResult.Status.SUCCEEDED,
                    Map.of("provider", id), List.of(), Map.of(), List.of());
        }
    }

    /** Provider whose availability can be toggled (mimics connection state). */
    private static final class ToggleProvider implements CapabilityProvider {
        private final String id;
        private volatile boolean available;

        ToggleProvider(String id, boolean available) {
            this.id = id;
            this.available = available;
        }

        void setAvailable(boolean available) {
            this.available = available;
        }

        @Override
        public String providerName() {
            return "toggle";
        }

        @Override
        public Collection<CapabilityDescriptor> descriptorsFor(CapabilityQueryContext context) {
            return available ? List.of(descriptorFor(id).orElseThrow()) : List.of();
        }

        @Override
        public boolean canHandle(String capabilityId) {
            return id.equals(capabilityId);
        }

        @Override
        public Optional<CapabilityDescriptor> descriptorFor(String capabilityId) {
            if (!id.equals(capabilityId) || !available) {
                return Optional.empty();
            }
            return Optional.of(new CapabilityDescriptor(id, "1", "toggle " + id,
                    Map.of("type", "object"), Map.of(), true, SideEffectClass.NONE,
                    List.of(), List.of()));
        }

        @Override
        public CapabilityResult invoke(CapabilityInvocation invocation) {
            return new CapabilityResult(invocation.invocationId(), invocation.invocationKey(),
                    id, CapabilityResult.Status.SUCCEEDED, Map.of(), List.of(), Map.of(), List.of());
        }
    }

    /** Provider that counts invocations (proves adapter bridging dispatches). */
    private static final class CountingProvider implements CapabilityProvider {
        private final String id;
        private int calls;

        CountingProvider(String id) {
            this.id = id;
        }

        int calls() {
            return calls;
        }

        @Override
        public String providerName() {
            return "counting";
        }

        @Override
        public Collection<CapabilityDescriptor> descriptorsFor(CapabilityQueryContext context) {
            return List.of(descriptorFor(id).orElseThrow());
        }

        @Override
        public boolean canHandle(String capabilityId) {
            return id.equals(capabilityId);
        }

        @Override
        public Optional<CapabilityDescriptor> descriptorFor(String capabilityId) {
            return id.equals(capabilityId)
                    ? Optional.of(new CapabilityDescriptor(id, "1", "counting " + id,
                            Map.of("type", "object"), Map.of(), true, SideEffectClass.NONE,
                            List.of(), List.of()))
                    : Optional.empty();
        }

        @Override
        public CapabilityResult invoke(CapabilityInvocation invocation) {
            calls++;
            return new CapabilityResult(invocation.invocationId(), invocation.invocationKey(),
                    id, CapabilityResult.Status.SUCCEEDED,
                    Map.of("provider", id), List.of(), Map.of(), List.of());
        }
    }
}