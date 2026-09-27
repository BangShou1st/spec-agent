package com.specagent.agent.runtime;

import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:WorkerStartupGateTest.java
 *
 * 测试目标:worker 轮询门控的有效组合(1.1)——
 * - worker 开 + 启动恢复开:就绪后先收敛孤儿再开放轮询;
 * - worker 开 + 启动恢复关:应用就绪后立即开放轮询,新任务可被认领
 *   (关闭恢复绝不等价于队列永久停摆);
 * - worker 关:门控与轮询器都不存在。
 * 每个组合一个独立 Spring 上下文(条件装配随属性变化)。
 */
class WorkerStartupGateTest {

    @SpringBootTest
    @ActiveProfiles("test")
    @org.springframework.test.annotation.DirtiesContext(
            classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
            "spec.agent.brain.worker.enabled=true",
            "spec.agent.brain.worker.startup-recovery=true",
    })
    static class RecoveryEnabledTest {

        @Autowired WorkerPollingGate gate;

        @Test
        void pollingOpensAfterStartupRecoveryFinishes() {
            // ApplicationReadyEvent 在上下文就绪时已触发:恢复已完成、门控开放
            assertThat(gate.isStartupRecoveryEnabled()).isTrue();
            assertThat(gate.isPollingOpen()).isTrue();
            assertThat(gate.isPollingAllowed()).isTrue();
            assertThat(gate.getRecoveryError()).isNull();
        }
    }

    @SpringBootTest
    @ActiveProfiles("test")
    @org.springframework.test.annotation.DirtiesContext(
            classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
    @TestPropertySource(properties = {
            "spec.agent.brain.worker.enabled=true",
            "spec.agent.brain.worker.startup-recovery=false",
    })
    static class RecoveryDisabledTest {

        @Autowired WorkerPollingGate gate;
        @Autowired RunWorkerPoller poller;
        @Autowired ProjectService projectService;
        @Autowired RunService runService;
        @Autowired AgentRunService agentRunService;

        /**
         * 核心回归:启动恢复关闭时,应用就绪后轮询立即开放,排队中的新
         * 任务可以被认领——修复"恢复监听器不存在 → recoveryCompleted 永远
         * false → worker 永久暂停"的缺陷。
         */
        @Test
        void pollingOpensImmediatelyAndClaimsQueuedRuns() {
            assertThat(gate.isStartupRecoveryEnabled()).isFalse();
            assertThat(gate.isPollingOpen()).isTrue();
            assertThat(gate.isPollingAllowed()).isTrue();

            var project = projectService.createProject("恢复关闭仍可认领 " + UUID.randomUUID());
            var run = runService.createQueuedDraftQuestion(project.id());
            poller.poll();
            // 认领后 worker 会实际执行(fake 网关),状态必然离开 created——
            // 修复前 poll() 在门控未开时直接返回,run 永远停留在 created。
            assertThat(agentRunService.getRun(run.id()).orElseThrow().status())
                    .isNotEqualTo(AgentRunStatus.CREATED);
        }
    }

    @SpringBootTest
    @ActiveProfiles("test")
    @TestPropertySource(properties = {
            "spec.agent.brain.worker.enabled=false",
    })
    static class WorkerDisabledTest {

        @Autowired org.springframework.beans.factory.ObjectProvider<WorkerPollingGate> gate;
        @Autowired org.springframework.beans.factory.ObjectProvider<RunWorkerPoller> poller;

        @Test
        void gateAndPollerAreAbsentWhenWorkerDisabled() {
            assertThat(gate.getIfAvailable()).isNull();
            assertThat(poller.getIfAvailable()).isNull();
        }
    }
}
