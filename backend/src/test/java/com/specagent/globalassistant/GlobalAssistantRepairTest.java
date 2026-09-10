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
import com.specagent.globalassistant.model.GlobalAssistantModelException;
import com.specagent.globalassistant.model.GlobalAssistantPromptRenderer;
import com.specagent.globalassistant.runtime.GlobalAssistantRuntime;
import com.specagent.globalassistant.runtime.GlobalAssistantRuntimeProperties;
import com.specagent.globalassistant.runtime.GlobalAssistantToolArgumentCanonicalizer;
import com.specagent.model.inference.ModelInferenceGateway;
import com.specagent.model.inference.ModelInferenceRequest;
import com.specagent.model.inference.ModelInferenceResponse;
import com.specagent.project.ProjectService;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bounded structural repair: one repair inference per rejected decision,
 * no Tool replay, cancellation preserved, no semantic retry.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GlobalAssistantRepairTest {
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
    @Autowired com.specagent.globalassistant.runtime.GlobalAssistantRunLifecycleService lifecycle;
    @Autowired com.specagent.globalassistant.stream.GlobalAssistantRunEventService runEvents;
    @Autowired com.specagent.globalassistant.runtime.GlobalAssistantUiActionValidator uiValidator;
    @Autowired com.specagent.globalassistant.model.GlobalAssistantSummaryService summaries;
    @Autowired ProjectService projects;
    @Autowired ObjectMapper mapper;

    record ScriptedBrain(GlobalAssistantRuntime runtime, AtomicInteger calls, List<String> callTypes) {}

    private ScriptedBrain runtimeWithScripts(Queue<String> scripts, java.util.function.Consumer<ModelInferenceRequest> onCall) {
        AtomicInteger calls = new AtomicInteger();
        List<String> callTypes = new java.util.concurrent.CopyOnWriteArrayList<>();
        ModelInferenceGateway stub = request -> {
            calls.incrementAndGet();
            callTypes.add(request.callType());
            if (onCall != null) {
                onCall.accept(request);
            }
            String content = scripts.poll();
            if (content == null) {
                throw new IllegalStateException("no more scripted outputs");
            }
            if (content.startsWith("THROW:")) {
                String code = content.substring("THROW:".length());
                throw new GlobalAssistantModelException(code, "scripted failure");
            }
            return new ModelInferenceResponse(content, "stop", 0, 0);
        };
        GlobalAssistantBrain brain = new GlobalAssistantBrain(renderer, stub, parser, validator);
        GlobalAssistantRuntime runtime = new GlobalAssistantRuntime(conversations, contextBuilder, brain,
                capabilities, runs, canonicalizer, budgets, lifecycle, runEvents, uiValidator, summaries);
        return new ScriptedBrain(runtime, calls, callTypes);
    }

    private GlobalAssistantRun newRun(GlobalAssistantThread thread) {
        return conversations.createRun(thread.id(), "v1", "v1", "fp");
    }

    private long toolStarts(UUID runId, String capabilityId) {
        return events.findByRun(runId).stream()
                .filter(e -> e.type().equals("TOOL_STARTED"))
                .filter(e -> capabilityId.equals(String.valueOf(e.payload().get("capabilityId"))))
                .count();
    }

    @Test
    void durableToolExecutesOnceAcrossRepair() {
        String title = "Repair Durable " + UUID.randomUUID();
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = newRun(thread);
        Queue<String> scripts = new ArrayDeque<>(List.of(
                "{\"kind\":\"TOOL\",\"toolRequest\":{\"capabilityId\":\"project.create\",\"arguments\":{\"title\":\"" + title + "\"}}}",
                "this is not json",
                """
                {"kind":"FINAL","assistantText":"Created."}
                """));
        ScriptedBrain scripted = runtimeWithScripts(scripts, null);
        scripted.runtime().executeRun(thread.id(), run.id(), "create " + title,
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.COMPLETED);
        assertThat(toolStarts(run.id(), "project.create")).isEqualTo(1);
        assertThat(projects.listProjects().stream().filter(p -> title.equals(p.title())).count()).isEqualTo(1);
        assertThat(scripted.calls().get()).isEqualTo(3);
        assertThat(scripted.callTypes()).containsExactly(
                GlobalAssistantBrain.DECISION_CALL_TYPE,
                GlobalAssistantBrain.DECISION_CALL_TYPE,
                GlobalAssistantBrain.DECISION_REPAIR_CALL_TYPE);
    }

    @Test
    void readOnlySearchExecutesOnceAcrossRepair() {
        com.specagent.project.Project project = projects.createProject("Repair Search " + UUID.randomUUID());
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = newRun(thread);
        Queue<String> scripts = new ArrayDeque<>(List.of(
                "{\"kind\":\"TOOL\",\"toolRequest\":{\"capabilityId\":\"project.search\",\"arguments\":{\"query\":\"" + project.title().substring(0, 8) + "\"}}}",
                "{broken",
                """
                {"kind":"FINAL","assistantText":"Found it."}
                """));
        ScriptedBrain scripted = runtimeWithScripts(scripts, null);
        scripted.runtime().executeRun(thread.id(), run.id(), "find it",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.COMPLETED);
        assertThat(toolStarts(run.id(), "project.search")).isEqualTo(1);
        assertThat(scripted.calls().get()).isEqualTo(3);
    }

    @Test
    void invalidTwiceFailsWithoutThirdCall() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = newRun(thread);
        Queue<String> scripts = new ArrayDeque<>(List.of("nope", "still nope"));
        ScriptedBrain scripted = runtimeWithScripts(scripts, null);
        scripted.runtime().executeRun(thread.id(), run.id(), "hi",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        GlobalAssistantRun finished = runs.findById(run.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(GlobalAssistantRunStatus.FAILED);
        assertThat(finished.errorCode()).isEqualTo("MODEL_INVALID_RESPONSE");
        assertThat(scripted.calls().get()).isEqualTo(2);
        assertThat(scripted.callTypes()).containsExactly(
                GlobalAssistantBrain.DECISION_CALL_TYPE,
                GlobalAssistantBrain.DECISION_REPAIR_CALL_TYPE);
    }

    @Test
    void validFirstDecisionNeverRepairs() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = newRun(thread);
        Queue<String> scripts = new ArrayDeque<>(List.of(
                """
                {"kind":"FINAL","assistantText":"Hello."}
                """));
        ScriptedBrain scripted = runtimeWithScripts(scripts, null);
        scripted.runtime().executeRun(thread.id(), run.id(), "hi",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.COMPLETED);
        assertThat(scripted.calls().get()).isEqualTo(1);
        assertThat(scripted.callTypes()).containsExactly(GlobalAssistantBrain.DECISION_CALL_TYPE);
    }

    @Test
    void providerFailureDoesNotRepair() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = newRun(thread);
        Queue<String> scripts = new ArrayDeque<>(List.of("THROW:MODEL_UNAVAILABLE"));
        ScriptedBrain scripted = runtimeWithScripts(scripts, null);
        scripted.runtime().executeRun(thread.id(), run.id(), "hi",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        GlobalAssistantRun finished = runs.findById(run.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(GlobalAssistantRunStatus.FAILED);
        assertThat(finished.errorCode()).isEqualTo("MODEL_UNAVAILABLE");
        assertThat(scripted.calls().get()).isEqualTo(1);
    }

    @Test
    void cancelBeforeRepairSkipsRepairCall() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = newRun(thread);
        Queue<String> scripts = new ArrayDeque<>(List.of(
                "bad json",
                """
                {"kind":"FINAL","assistantText":"Late."}
                """));
        ScriptedBrain scripted = runtimeWithScripts(scripts,
                request -> {
                    if (request.callType().equals(GlobalAssistantBrain.DECISION_CALL_TYPE)) {
                        runs.requestCancel(run.id());
                    }
                });
        scripted.runtime().executeRun(thread.id(), run.id(), "hi",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.CANCELLED);
        assertThat(scripted.calls().get()).isEqualTo(1);
    }

    @Test
    void cancelAfterRepairStopsRun() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = newRun(thread);
        Queue<String> scripts = new ArrayDeque<>(List.of(
                "bad json",
                """
                {"kind":"FINAL","assistantText":"Late but valid."}
                """));
        ScriptedBrain scripted = runtimeWithScripts(scripts,
                request -> {
                    if (request.callType().equals(GlobalAssistantBrain.DECISION_REPAIR_CALL_TYPE)) {
                        runs.requestCancel(run.id());
                    }
                });
        scripted.runtime().executeRun(thread.id(), run.id(), "hi",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.CANCELLED);
        assertThat(scripted.calls().get()).isEqualTo(2);
    }

    @Test
    void validSemanticDecisionIsNeverRetried() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = newRun(thread);
        Queue<String> scripts = new ArrayDeque<>(List.of(
                """
                {"kind":"FINAL","assistantText":"Here is some general help."}
                """));
        ScriptedBrain scripted = runtimeWithScripts(scripts, null);
        scripted.runtime().executeRun(thread.id(), run.id(), "show my most recent workspaces",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.COMPLETED);
        assertThat(scripted.calls().get()).isEqualTo(1);
    }
}
