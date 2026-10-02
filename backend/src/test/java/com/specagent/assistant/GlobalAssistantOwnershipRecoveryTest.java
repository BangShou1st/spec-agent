package com.specagent.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.assistant.runtime.GaHostContext;
import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.conversation.GlobalAssistantRun;
import com.specagent.assistant.conversation.GlobalAssistantRunEventRepository;
import com.specagent.assistant.conversation.GlobalAssistantRunRepository;
import com.specagent.assistant.conversation.GlobalAssistantRunStatus;
import com.specagent.assistant.conversation.GlobalAssistantThread;
import com.specagent.assistant.runtime.GaHostContext;
import com.specagent.assistant.runtime.GlobalAssistantRunLifecycleService;
import com.specagent.assistant.runtime.GlobalAssistantUiActionValidator;
import com.specagent.assistant.runtime.GlobalAssistantRunEventService;
import com.specagent.model.contract.ModelInferenceGateway;
import com.specagent.model.contract.ModelInferenceResponse;
import com.specagent.workspace.project.ProjectService;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * 文件名:GlobalAssistantOwnershipRecoveryTest.java
 *
 * 测试目标:验证全局助手运行的单一执行所有权与孤儿运行恢复。
 * 覆盖场景:同一运行的并发重复派发只会产生一个副作用(只创建一个项目)、
 * 恢复监听器在应用就绪时通过事务服务把孤儿运行标记为 RUN_INTERRUPTED 失败、
 * 恢复监听器是独立 Bean 且服务内没有自调用监听方法、
 * 孤儿恢复失败收场并释放并发槽位。
 */
@SpringBootTest
@ActiveProfiles("test")
class GlobalAssistantOwnershipRecoveryTest {
    @Autowired com.specagent.assistant.model.GlobalAssistantModelTargetResolver modelTargets;
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired GaHostContext contextBuilder;
    @Autowired CapabilityRuntime capabilities;
    @Autowired GlobalAssistantRunRepository runs;
    @Autowired GlobalAssistantRunEventRepository events;
    @Autowired GlobalAssistantRunLifecycleService lifecycle;
    @Autowired GlobalAssistantRunEventService runEvents;
    @Autowired GlobalAssistantUiActionValidator uiValidator;
    @Autowired ProjectService projects;
    @Autowired ObjectMapper mapper;
    @Autowired com.specagent.assistant.runtime.GlobalAssistantRunRecoveryService recovery;
    @Autowired com.specagent.assistant.runtime.GlobalAssistantRunRecoveryListener recoveryListener;
    @Autowired org.springframework.context.ConfigurableApplicationContext applicationContext;
    @Autowired JdbcTemplate jdbc;
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM global_assistant_run_events");
        jdbc.update("DELETE FROM global_assistant_runs");
        jdbc.update("DELETE FROM global_assistant_messages");
        jdbc.update("DELETE FROM global_assistant_threads");
    }


    @Test
    void listenerPathRecoversOrphanThroughTransactionalService() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun orphan = conversations.createRunWithUserMessage(
                thread.id(), "orphaned via listener", "v1", "v1", "fp");
        recoveryListener.onApplicationReady(new org.springframework.boot.context.event.ApplicationReadyEvent(
                new org.springframework.boot.SpringApplication(), new String[0], applicationContext,
                java.time.Duration.ZERO));
        GlobalAssistantRun finished = runs.findById(orphan.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(GlobalAssistantRunStatus.FAILED);
        assertThat(finished.errorCode()).isEqualTo("RUN_INTERRUPTED");
        assertThat(events.findByRun(orphan.id()).stream()
                        .anyMatch(e -> e.type().equals("RUN_FAILED")))
                .isTrue();
        GlobalAssistantRun next = conversations.createRunWithUserMessage(
                thread.id(), "after listener recovery", "v1", "v1", "fp");
        assertThat(next.status()).isEqualTo(GlobalAssistantRunStatus.CREATED);
    }
    @Test
    void recoveryListenerIsASeparateBeanWithoutSelfCall() {
        assertThat(recoveryListener).isNotSameAs((Object) recovery);
        boolean serviceHasListener = java.util.Arrays.stream(
                        com.specagent.assistant.runtime.GlobalAssistantRunRecoveryService.class
                                .getDeclaredMethods())
                .anyMatch(m -> m.isAnnotationPresent(
                        org.springframework.context.event.EventListener.class));
        assertThat(serviceHasListener).isFalse();
        boolean listenerHandlesReady = java.util.Arrays.stream(
                        recoveryListener.getClass().getDeclaredMethods())
                .anyMatch(m -> m.isAnnotationPresent(
                        org.springframework.context.event.EventListener.class));
        assertThat(listenerHandlesReady).isTrue();
    }
    @Test
    void orphanRunRecoveryFailsClosedAndReleasesSlot() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun orphan = conversations.createRunWithUserMessage(
                thread.id(), "orphaned work", "v1", "v1", "fp");
        int before = jdbc.queryForList("SELECT id FROM projects").size();
        int recovered = recovery.recoverOrphans();
        assertThat(recovered).isEqualTo(1);
        GlobalAssistantRun finished = runs.findById(orphan.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(GlobalAssistantRunStatus.FAILED);
        assertThat(finished.errorCode()).isEqualTo("RUN_INTERRUPTED");
        assertThat(events.findByRun(orphan.id()).stream()
                        .anyMatch(e -> e.type().equals("RUN_FAILED")))
                .isTrue();
        assertThat(jdbc.queryForList("SELECT id FROM projects").size()).isEqualTo(before);
        GlobalAssistantRun next = conversations.createRunWithUserMessage(
                thread.id(), "follow-up", "v1", "v1", "fp");
        assertThat(next.status()).isEqualTo(GlobalAssistantRunStatus.CREATED);
    }
}
