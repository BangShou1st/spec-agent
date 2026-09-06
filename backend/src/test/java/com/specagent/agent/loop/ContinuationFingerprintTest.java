package com.specagent.agent.loop;

import com.specagent.agent.AgentRunRequestFingerprint;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice 0: deterministic continuation fingerprints carry loop identity only.
 *
 * <p>No semantic fields (conflict, goal, planning flags) may enter the
 * fingerprint; it names a child slot in a chain, nothing more.
 */
class ContinuationFingerprintTest {

    private final UUID projectId = UUID.randomUUID();
    private final UUID parentId = UUID.randomUUID();

    @Test
    void sameInputsProduceSameFingerprint() {
        assertThat(AgentRunRequestFingerprint.forContinuation(projectId, parentId, 1))
                .isEqualTo(AgentRunRequestFingerprint.forContinuation(projectId, parentId, 1));
    }

    @Test
    void differentParentProducesDifferentFingerprint() {
        assertThat(AgentRunRequestFingerprint.forContinuation(projectId, parentId, 1))
                .isNotEqualTo(AgentRunRequestFingerprint.forContinuation(
                        projectId, UUID.randomUUID(), 1));
    }

    @Test
    void differentCycleProducesDifferentFingerprint() {
        assertThat(AgentRunRequestFingerprint.forContinuation(projectId, parentId, 1))
                .isNotEqualTo(AgentRunRequestFingerprint.forContinuation(projectId, parentId, 2));
    }

    @Test
    void differentProjectProducesDifferentFingerprint() {
        assertThat(AgentRunRequestFingerprint.forContinuation(projectId, parentId, 1))
                .isNotEqualTo(AgentRunRequestFingerprint.forContinuation(
                        UUID.randomUUID(), parentId, 1));
    }
}
