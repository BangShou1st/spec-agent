package com.specagent.globalassistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.globalassistant.context.GlobalAssistantContextBuilder;
import com.specagent.globalassistant.conversation.GlobalAssistantConversationService;
import com.specagent.globalassistant.conversation.GlobalAssistantRun;
import com.specagent.globalassistant.conversation.GlobalAssistantRunEventRepository;
import com.specagent.globalassistant.conversation.GlobalAssistantRunRepository;
import com.specagent.globalassistant.conversation.GlobalAssistantRunStatus;
import com.specagent.globalassistant.conversation.GlobalAssistantThread;
import com.specagent.globalassistant.model.GlobalAssistantBrain;
import com.specagent.globalassistant.model.GlobalAssistantDecisionParser;
import com.specagent.globalassistant.model.GlobalAssistantDecisionValidator;
import com.specagent.globalassistant.model.GlobalAssistantPromptRenderer;
import com.specagent.globalassistant.model.GlobalAssistantSummaryService;
import com.specagent.globalassistant.runtime.GlobalAssistantRunLifecycleService;
import com.specagent.globalassistant.runtime.GlobalAssistantRuntime;
import com.specagent.globalassistant.runtime.GlobalAssistantRuntimeProperties;
import com.specagent.globalassistant.runtime.GlobalAssistantToolArgumentCanonicalizer;
import com.specagent.globalassistant.runtime.GlobalAssistantUiActionValidator;
import com.specagent.globalassistant.stream.GlobalAssistantRunEventService;
import com.specagent.model.inference.ModelInferenceGateway;
import com.specagent.model.inference.ModelInferenceResponse;
import com.specagent.project.ProjectService;
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
 * FIX F/S RED: single execution owner + orphan recovery.
 */
@SpringBootTest
@ActiveProfiles("test")
class GlobalAssistantOwnershipRecoveryTest {
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired GlobalAssistantContextBuilder contextBuilder;
    @Autowired GlobalAssistantPromptRenderer renderer;
    @Autowired GlobalAssistantDecisionParser parser;
    @Autowired GlobalAssistantDecisionValidator validator;
    @Autowired CapabilityRuntime capabilities;
    @Autowired GlobalAssistantRunRepository runs;
    @Autowired GlobalAssistantRunEventRepository events;
    @Autowired GlobalAssistantToolArgumentCanonicalizer canonicalizer;
    @Autowired GlobalAssistantRuntimeProperties budgets;
    @Autowired GlobalAssistantRunLifecycleService lifecycle;
    @Autowired GlobalAssistantRunEventService runEvents;
    @Autowired GlobalAssistantUiActionValidator uiValidator;
    @Autowired GlobalAssistantSummaryService summaries;
    @Autowired ProjectService projects;
    @Autowired ObjectMapper mapper;
    @Autowired com.specagent.globalassistant.runtime.GlobalAssistantRunRecoveryService recovery;
    @Autowired com.specagent.globalassistant.runtime.GlobalAssistantRunRecoveryListener recoveryListener;
    @Autowired org.springframework.context.ConfigurableApplicationContext applicationContext;
    @Autowired JdbcTemplate jdbc;
    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM global_assistant_run_events");
        jdbc.update("DELETE FROM global_assistant_runs");
        jdbc.update("DELETE FROM global_assistant_messages");
        jdbc.update("DELETE FROM global_assistant_threads");
    }
    private GlobalAssistantRuntime runtimeFor(Queue<String> scripts) {
        ModelInferenceGateway stub = request -> new ModelInferenceResponse(scripts.poll(), "stop", 0, 0);
        GlobalAssistantBrain brain = new GlobalAssistantBrain(renderer, stub, parser, validator);
        return new GlobalAssistantRuntime(conversations, contextBuilder, brain, capabilities,
                runs, canonicalizer, budgets, lifecycle, runEvents, uiValidator, summaries);
    }
    @Test
    void duplicateDispatchCreatesOnlyOneProject() throws Exception {
        GlobalAssistantThread thread = conversations.createThread();
        String title = "Dup Dispatch " + UUID.randomUUID();
        GlobalAssistantRun run = conversations.createRunWithUserMessage(thread.id(),
                "create " + title, "v1", "v1", "fp");
         String createJson = "{\"kind\":\"TOOL\", \"toolRequest\":{\"capabilityId\":\"project.create\","
                + " \"arguments\":{\"title\":\"" + title + "\"}}}";
         String doneJson = "{\"kind\":\"FINAL\", \"assistantText\":\"Created.\"}";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                runtimeFor(new ArrayDeque<>(List.of(createJson, doneJson)))
                        .executeRun(thread.id(), run.id(), "create " + title,
                                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
                return null;
            });
            Future<?> second = pool.submit(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                runtimeFor(new ArrayDeque<>(List.of(createJson, doneJson)))
                        .executeRun(thread.id(), run.id(), "create " + title,
                                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
                return null;
            });
            start.countDown();
            first.get();
            second.get();
        } finally {
            pool.shutdownNow();
        }
        List<Map<String, Object>> matching = jdbc.queryForList(
                "SELECT id FROM projects WHERE title = ?", title);
        assertThat(matching).hasSize(1);
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.COMPLETED);
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
                        com.specagent.globalassistant.runtime.GlobalAssistantRunRecoveryService.class
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
