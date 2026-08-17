package com.specagent.context;

import com.specagent.patch.AnswerPatch;
import com.specagent.patch.Claim;
import com.specagent.patch.ClaimKind;
import com.specagent.patch.ClaimStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequirementStateBuilderTest {

    private final RequirementStateBuilder builder = new RequirementStateBuilder(null);

    private AnswerPatch patch(UUID routeId, UUID nodeId, UUID answerId, List<Claim> claims) {
        return new AnswerPatch(UUID.randomUUID(), UUID.randomUUID(), routeId, nodeId, answerId,
                claims, null, Instant.now());
    }

    @Test
    void requirementStateCanBeRebuiltFromPatches() {
        UUID routeId = UUID.randomUUID();
        UUID n1 = UUID.randomUUID();
        UUID n2 = UUID.randomUUID();
        UUID a1 = UUID.randomUUID();
        UUID a2 = UUID.randomUUID();

        Claim c1 = Claim.of(ClaimKind.GOAL, "goal", ClaimStatus.CONFIRMED, n1, a1);
        Claim c2 = Claim.of(ClaimKind.CONSTRAINT, "constraint", ClaimStatus.ASSUMED, n2, a2);

        AnswerPatch p1 = patch(routeId, n1, a1, List.of(c1));
        AnswerPatch p2 = patch(routeId, n2, a2, List.of(c2));

        RequirementState state = builder.rebuild(List.of(p1, p2));

        assertThat(state.routeId()).isEqualTo(routeId);
        assertThat(state.claims()).hasSize(2);
        assertThat(state.confirmed()).hasSize(1);
        assertThat(state.unresolved()).hasSize(1);
    }

    @Test
    void rebuildIsDeterministicAcrossReplays() {
        UUID routeId = UUID.randomUUID();
        UUID n1 = UUID.randomUUID();
        UUID a1 = UUID.randomUUID();
        Claim c1 = Claim.of(ClaimKind.SCOPE, "scope", ClaimStatus.CONFIRMED, n1, a1);
        AnswerPatch p1 = patch(routeId, n1, a1, List.of(c1));

        RequirementState first = builder.rebuild(List.of(p1));
        RequirementState second = builder.rebuild(List.of(p1));

        assertThat(first.claims()).containsExactlyElementsOf(second.claims());
        assertThat(first.builtAt()).isNotNull();
        assertThat(second.builtAt()).isNotNull();
    }
}
