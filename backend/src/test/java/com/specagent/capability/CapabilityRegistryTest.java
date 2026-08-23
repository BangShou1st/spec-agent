package com.specagent.capability;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Registry ownership rules: discovery, permission filtering (invisible — not
 * merely blocked — when permissions are missing), and duplicate rejection.
 */
class CapabilityRegistryTest {

    @Test
    void permissionlessDescriptorIsVisibleWithEmptyGrants() {
        CapabilityRegistry registry = new CapabilityRegistry(List.of(
                stub("cap.open", Set.of())));

        assertThat(registry.descriptorsFor(Set.of()))
                .extracting(CapabilityDescriptor::capabilityId)
                .containsExactly("cap.open");
    }

    @Test
    void descriptorsWithUngrantedPermissionsAreInvisible() {
        CapabilityRegistry registry = new CapabilityRegistry(List.of(
                stub("cap.open", Set.of()),
                stub("cap.secure", Set.of("mcp:external"))));

        assertThat(registry.descriptorsFor(Set.of()))
                .extracting(CapabilityDescriptor::capabilityId)
                .containsExactly("cap.open");
        assertThat(registry.descriptorsFor(Set.of("mcp:external")))
                .extracting(CapabilityDescriptor::capabilityId)
                .containsExactly("cap.open", "cap.secure");
    }

    @Test
    void duplicateCapabilityIdsAreRejected() {
        assertThatThrownBy(() -> new CapabilityRegistry(List.of(
                stub("cap.dup", Set.of()), stub("cap.dup", Set.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Duplicate capability id");
    }

    @Test
    void lookupFindsDescriptorAndAdapter() {
        CapabilityRegistry registry = new CapabilityRegistry(List.of(stub("cap.x", Set.of())));

        assertThat(registry.findDescriptor("cap.x")).isPresent();
        assertThat(registry.findDescriptor("cap.missing")).isEmpty();
        assertThat(registry.findAdapter("cap.x")).isPresent();
    }

    private CapabilityAdapter stub(String id, Set<String> permissions) {
        return new CapabilityAdapter() {
            @Override
            public CapabilityDescriptor descriptor() {
                return new CapabilityDescriptor(id, "1", "stub", null, null, true,
                        SideEffectClass.NONE, List.copyOf(permissions), List.of());
            }

            @Override
            public CapabilityResult invoke(CapabilityInvocation invocation) {
                return new CapabilityResult(invocation.invocationId(), invocation.invocationKey(),
                        id, CapabilityResult.Status.SUCCEEDED, null, null, null, null);
            }
        };
    }
}
