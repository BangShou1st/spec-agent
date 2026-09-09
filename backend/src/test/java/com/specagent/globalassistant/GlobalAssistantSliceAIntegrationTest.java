package com.specagent.globalassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.specagent.capability.CapabilityResult;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.capability.CapabilityInvocationRepository;
import com.specagent.globalassistant.conversation.GlobalAssistantConversationService;
import com.specagent.globalassistant.conversation.GlobalAssistantEventType;
import com.specagent.globalassistant.conversation.GlobalAssistantMessage;
import com.specagent.globalassistant.conversation.GlobalAssistantRun;
import com.specagent.globalassistant.conversation.GlobalAssistantRunActiveException;
import com.specagent.globalassistant.conversation.GlobalAssistantRunEvent;
import com.specagent.globalassistant.conversation.GlobalAssistantRunEventRepository;
import com.specagent.globalassistant.conversation.GlobalAssistantRunRepository;
import com.specagent.globalassistant.conversation.GlobalAssistantRunStatus;
import com.specagent.globalassistant.conversation.GlobalAssistantThread;
import com.specagent.globalassistant.conversation.GlobalAssistantThreadRepository;
import com.specagent.globalassistant.conversation.GlobalAssistantWorkingState;
import com.specagent.project.ProjectService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slice A: threads/messages/runs/events, working-state/summary CAS,
 * application-scoped invocations, single-active invariant, cancel signal,
 * serialized event sequence (real PostgreSQL).
 */
@SpringBootTest
@ActiveProfiles("test")
class GlobalAssistantSliceAIntegrationTest {
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired GlobalAssistantThreadRepository threads;
    @Autowired GlobalAssistantRunRepository runs;
    @Autowired GlobalAssistantRunEventRepository events;
    @Autowired CapabilityRuntime capabilities;
    @Autowired CapabilityInvocationRepository invocations;
    @Autowired ProjectService projects;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.junit.jupiter.api.AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM global_assistant_run_events");
        jdbc.update("DELETE FROM global_assistant_runs");
        jdbc.update("DELETE FROM global_assistant_messages");
        jdbc.update("DELETE FROM global_assistant_threads");
        jdbc.update("DELETE FROM capability_invocations WHERE invocation_key LIKE 'ga-test-%'");
    }
    @Test
    void threadMessageOrderingIsDeterministic() {
        GlobalAssistantThread thread = conversations.createThread();
        conversations.appendUserMessage(thread.id(), "first", null);
        conversations.appendAssistantMessage(thread.id(), "hello", null);
        conversations.appendUserMessage(thread.id(), "second", null);
        List<GlobalAssistantMessage> stored =
                conversations.listMessages(thread.id());
        assertThat(stored).hasSize(3);
        assertThat(stored.get(0).content()).isEqualTo("first");
        assertThat(stored.get(1).role()).isEqualTo(GlobalAssistantMessage.Role.ASSISTANT);
        assertThat(stored.get(2).content()).isEqualTo("second");
    }
    @Test
    void workingStateCasFailsClosedOnConflict() {
        GlobalAssistantThread thread = conversations.createThread();
        conversations.writeWorkingState(thread.id(),
                new GlobalAssistantWorkingState("find mail", List.of(), null, null, List.of()), 0);
        assertThat(conversations.readWorkingState(thread.id()).goal()).isEqualTo("find mail");
        assertThatThrownBy(() -> conversations.writeWorkingState(thread.id(),
                        GlobalAssistantWorkingState.empty(), 0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("version conflict");
    }
    @Test
    void summaryCasFailsClosedOnConflict() {
        GlobalAssistantThread thread = conversations.createThread();
        conversations.writeSummary(thread.id(), "goals preserved", 0);
        assertThat(threads.findById(thread.id()).orElseThrow().summary())
                .isEqualTo("goals preserved");
        assertThatThrownBy(() -> conversations.writeSummary(thread.id(), "overwrite", 0))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test
    void applicationScopedInvocationPersistsNullProject() {
        CapabilityResult result = capabilities.invokeApplicationScoped(
                "ga-test-" + UUID.randomUUID(), "project.list_recent", null, Map.of("limit", 2));
        assertThat(result.status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
        var record = invocations.findByInvocationKey(result.invocationKey()).orElseThrow();
        assertThat(record.projectId()).isNull();
    }
    @Test
    void projectScopedInvokeRejectsNullProjectId() {
        assertThatThrownBy(() -> capabilities.invoke(
                        "ga-test-" + UUID.randomUUID(), "project.list_recent", null, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invokeApplicationScoped");
    }
    @Test
    void applicationScopedIdempotencyReplaysWithoutSecondExecution() {
        String key = "ga-test-" + UUID.randomUUID();
        CapabilityResult first = capabilities.invokeApplicationScoped(
                key, "project.create", null, Map.of("title", "GA Idempotency " + UUID.randomUUID()));
        CapabilityResult replay = capabilities.invokeApplicationScoped(
                key, "project.create", null, Map.of("title", "GA Idempotency " + UUID.randomUUID()));
        assertThat(first.status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
        assertThat(replay.status()).isEqualTo(CapabilityResult.Status.REPLAYED);
        assertThat(replay.content().get("projectId")).isEqualTo(first.content().get("projectId"));
    }
    @Test
    void singleActiveRunIsEnforcedByDatabase() {
        GlobalAssistantThread thread = conversations.createThread();
        conversations.createRun(thread.id(), "v1", "v1", "fp");
        assertThatThrownBy(() -> conversations.createRun(thread.id(), "v1", "v1", "fp"))
                .isInstanceOf(GlobalAssistantRunActiveException.class);
    }
    @Test
    void cancelSignalDoesNotTerminalizeImmediately() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        assertThat(runs.requestCancel(run.id())).isTrue();
        GlobalAssistantRun reloaded = runs.findById(run.id()).orElseThrow();
        assertThat(reloaded.cancelRequestedAt()).isNotNull();
        assertThat(reloaded.status()).isIn(GlobalAssistantRunStatus.CREATED, GlobalAssistantRunStatus.RUNNING);
        // Idempotent second cancel keeps the same signal semantics.
        assertThat(runs.requestCancel(run.id())).isTrue();
        // Terminal runs never resurrect.
        runs.terminalize(run.id(), GlobalAssistantRunStatus.CANCELLED, "RUN_CANCELLED");
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.CANCELLED);
        assertThatThrownBy(
                        () -> runs.terminalize(run.id(), GlobalAssistantRunStatus.COMPLETED, null))
                .isInstanceOf(IllegalStateException.class);
    }
    @Test
    void terminalCancelIsIdempotentForReads() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        runs.terminalize(run.id(), GlobalAssistantRunStatus.COMPLETED, null);
        // Cancel on a terminal run changes nothing and never resurrects it.
        runs.requestCancel(run.id());
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.COMPLETED);
    }
    @Test
    void eventSequenceIsMonotonicPerRun() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        GlobalAssistantRunEvent first = events.append(run.id(), GlobalAssistantEventType.RUN_STARTED, Map.of());
        GlobalAssistantRunEvent second = events.append(run.id(), GlobalAssistantEventType.STATUS, Map.of());
        assertThat(first.sequence()).isEqualTo(1);
        assertThat(second.sequence()).isEqualTo(2);
        List<GlobalAssistantRunEvent> replay = events.findAfter(run.id(), 1);
        assertThat(replay).hasSize(1);
        assertThat(replay.get(0).sequence()).isEqualTo(2);
    }
    @Test
    void concurrentEventAppendsSerializeWithoutDuplicates() throws Exception {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        int writers = 5;
        ExecutorService pool = Executors.newFixedThreadPool(writers);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < writers; i++) {
                futures.add(pool.submit(() -> {
                    try {
                        start.await();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    events.append(run.id(), GlobalAssistantEventType.STATUS, Map.of("m", "hi"));
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
            List<GlobalAssistantRunEvent> stored = events.findByRun(run.id());
            assertThat(stored).hasSize(writers);
            assertThat(stored.stream().map(GlobalAssistantRunEvent::sequence).toList())
                    .containsExactlyInAnyOrder(1, 2, 3, 4, 5);
        } finally {
            pool.shutdownNow();
        }
    }
}
