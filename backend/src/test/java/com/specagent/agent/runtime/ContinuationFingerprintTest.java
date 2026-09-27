package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRunRequestFingerprint;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:ContinuationFingerprintTest.java
 *
 * 测试目标:Slice 0:确定性续跑指纹只携带循环身份。相同输入产生相同指纹;
 * 父 run、循环序号、项目任一不同则指纹不同。指纹中不得进入任何语义字段
 * (conflict、goal、planning 标志);它只命名链中的一个子槽位。
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
