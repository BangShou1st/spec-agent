package com.specagent.agent.gates;

import com.specagent.agent.contracts.AnswerPatchDraft;
import com.specagent.agent.contracts.ReflectionResult;
import com.specagent.common.Ids;
import com.specagent.patch.Claim;
import com.specagent.patch.ClaimKind;
import com.specagent.patch.ClaimStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PatchReflectionGateTest {

    private final PatchReflectionGate patchReflectionGate = new PatchReflectionGate();

    @Test
    void patchReflectionAcceptsConfirmedClaimWithSources() {
        ReflectionResult result = patchReflectionGate.validate(
                new AnswerPatchDraft(List.of(
                        Claim.of(ClaimKind.GOAL, "Build an app", ClaimStatus.CONFIRMED,
                                UUID.randomUUID(), UUID.randomUUID()))));

        assertThat(result.accepted()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void patchReflectionRejectsConfirmedClaimWithoutSourceNode() {
        ReflectionResult result = patchReflectionGate.validate(
                new AnswerPatchDraft(List.of(claim(ClaimStatus.CONFIRMED, "Build an app",
                        null, UUID.randomUUID()))));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors()).contains("Confirmed claim requires sourceNodeId");
    }

    @Test
    void patchReflectionRejectsConfirmedClaimWithoutSourceAnswer() {
        ReflectionResult result = patchReflectionGate.validate(
                new AnswerPatchDraft(List.of(claim(ClaimStatus.CONFIRMED, "Build an app",
                        UUID.randomUUID(), null))));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors()).contains("Confirmed claim requires sourceAnswerId");
    }

    @Test
    void patchReflectionRejectsBlankClaimText() {
        ReflectionResult result = patchReflectionGate.validate(
                new AnswerPatchDraft(List.of(claim(ClaimStatus.ASSUMED, " ",
                        UUID.randomUUID(), UUID.randomUUID()))));

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors()).contains("Claim text is required");
    }

    @Test
    void patchReflectionAcceptsAssumedClaimWithoutSources() {
        ReflectionResult result = patchReflectionGate.validate(
                new AnswerPatchDraft(List.of(claim(ClaimStatus.ASSUMED, "Single developer",
                        null, null))));

        assertThat(result.accepted()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void patchReflectionRejectsNullDraft() {
        ReflectionResult result = patchReflectionGate.validate(null);

        assertThat(result.accepted()).isFalse();
        assertThat(result.errors()).containsExactly("Answer patch draft is required");
    }

    private Claim claim(ClaimStatus status, String text, UUID sourceNodeId, UUID sourceAnswerId) {
        return new Claim(Ids.random(), ClaimKind.GOAL, text, status, null, sourceNodeId, sourceAnswerId);
    }
}