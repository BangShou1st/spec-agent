package com.specagent.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.assistant.runtime.GlobalAssistantContextBuilder;
import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.conversation.GlobalAssistantRun;
import com.specagent.assistant.conversation.GlobalAssistantRunEventRepository;
import com.specagent.assistant.conversation.GlobalAssistantRunRepository;
import com.specagent.assistant.conversation.GlobalAssistantRunStatus;
import com.specagent.assistant.conversation.GlobalAssistantThread;
import com.specagent.assistant.model.GlobalAssistantBrain;
import com.specagent.assistant.model.GlobalAssistantDecisionParser;
import com.specagent.assistant.model.GlobalAssistantDecisionValidator;
import com.specagent.assistant.model.GlobalAssistantPromptRenderer;
import com.specagent.assistant.runtime.GlobalAssistantRuntime;
import com.specagent.assistant.runtime.GlobalAssistantRuntimeProperties;
import com.specagent.assistant.runtime.GlobalAssistantToolArgumentCanonicalizer;
import com.specagent.assistant.runtime.GlobalAssistantStreamService;
import com.specagent.model.contract.ModelInferenceGateway;
import com.specagent.model.contract.ModelInferenceResponse;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Slice E: bounded loop, budgets, no-progress, cancellation, observations.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GlobalAssistantSliceERuntimeTest {
    @Autowired com.specagent.assistant.model.GlobalAssistantModelTargetResolver modelTargets;
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
    @Autowired com.specagent.assistant.runtime.GlobalAssistantRunLifecycleService lifecycle;
    @Autowired com.specagent.assistant.runtime.GlobalAssistantRunEventService runEvents;
    @Autowired com.specagent.assistant.runtime.GlobalAssistantUiActionValidator uiValidator;
    @Autowired com.specagent.assistant.runtime.GlobalAssistantSummaryService summaries;
    @Autowired ProjectService projects;
    @Autowired ObjectMapper mapper;
    private GlobalAssistantRuntime runtimeWithScripts(Queue<String> scripts) {
        ModelInferenceGateway stub = request -> new ModelInferenceResponse(scripts.poll(), "stop", 0, 0);
        GlobalAssistantBrain brain = new GlobalAssistantBrain(renderer, stub, parser, validator);
        return new GlobalAssistantRuntime(conversations, contextBuilder, brain, capabilities,
                runs, canonicalizer, budgets, lifecycle, runEvents, uiValidator, summaries, modelTargets);
    }
    @Test
    void toolLoopResolvesSearchThenFinalAnswer() {
        Project project = projects.createProject("Runtime Search Target " + UUID.randomUUID());
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        Queue<String> scripts = new ArrayDeque<>(List.of(
                 "{\"toolRequest\": {\"capabilityId\": \"project.search\", \"arguments\": {\"query\": \"" + project.title().substring(0, 8) + "\"}}, \"kind\":\"TOOL\"}",
                "{\"assistantText\": \"Found it.\", \"uiAction\": {\"destination\": \"PROJECT\", \"resourceId\": \""
                          + project.id() + "\"}, \"kind\":\"NAVIGATE\"}"));
        runtimeWithScripts(scripts).executeRun(thread.id(), run.id(), "open my project",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        GlobalAssistantRun finished = runs.findById(run.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(GlobalAssistantRunStatus.COMPLETED);
        assertThat(conversations.listMessages(thread.id()).stream()
                        .filter(m -> m.role().name().equals("ASSISTANT"))
                        .count())
                .isGreaterThanOrEqualTo(1);
    }
    @Test
    void noToolConversationalAnswerCompletesWithoutTools() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        Queue<String> scripts = new ArrayDeque<>(List.of(
                 "{\"assistantText\": \"You can organize projects by recency.\", \"kind\":\"FINAL\"}"));
        runtimeWithScripts(scripts).executeRun(thread.id(), run.id(), "how are projects organized?",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.COMPLETED);
        assertThat(events.findByRun(run.id()).stream()
                        .noneMatch(e -> e.type().equals("TOOL_STARTED")))
                .isTrue();
    }
    @Test
    void sameToolSameArgsWithoutProgressStopsHonestly() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        String toolJson =
                 "{\"toolRequest\": {\"capabilityId\": \"project.list_recent\", \"arguments\": {}}, \"kind\":\"TOOL\"}";
        Queue<String> scripts = new ArrayDeque<>(List.of(toolJson, toolJson,
                 "{\"assistantText\": \"Done.\", \"kind\":\"FINAL\"}"));
        runtimeWithScripts(scripts).executeRun(thread.id(), run.id(), "list recents",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.COMPLETED);
        long toolStarts = events.findByRun(run.id()).stream()
                .filter(e -> e.type().equals("TOOL_STARTED")).count();
        // No-progress stops before the second identical call executes.
        assertThat(toolStarts).isEqualTo(1);
    }
    @Test
    void cancelRequestedBeforeToolPreventsExecution() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        runs.requestCancel(run.id());
        Queue<String> scripts = new ArrayDeque<>(List.of(
                 "{\"toolRequest\": {\"capabilityId\": \"project.list_recent\", \"arguments\": {}}, \"kind\":\"TOOL\"}"));
        runtimeWithScripts(scripts).executeRun(thread.id(), run.id(), "list",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.CANCELLED);
        assertThat(events.findByRun(run.id()).stream()
                        .noneMatch(e -> e.type().equals("TOOL_STARTED")))
                .isTrue();
    }
    @Test
    void invalidToolDecisionFailsRunClosed() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        Queue<String> scripts = new ArrayDeque<>(List.of(
                 "{\"toolRequest\": {\"capabilityId\": \"skill.secret\", \"arguments\": {}}, \"kind\":\"TOOL\"}"));
        runtimeWithScripts(scripts).executeRun(thread.id(), run.id(), "do secret",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        GlobalAssistantRun finished = runs.findById(run.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(GlobalAssistantRunStatus.FAILED);
        assertThat(finished.errorCode()).isEqualTo("MODEL_INVALID_RESPONSE");
    }
    @Test
    void toolBudgetExhaustionFailsHonestly() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        Queue<String> scripts = new ArrayDeque<>();
        for (int i = 0; i < 7; i++) {
            scripts.add("{\"toolRequest\": {\"capabilityId\": \"project.list_recent\", \"arguments\": {\"limit\": "
                     + ((i % 10) + 1) + "}}, \"kind\":\"TOOL\"}");
        }
        runtimeWithScripts(scripts).executeRun(thread.id(), run.id(), "keep listing",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        GlobalAssistantRun finished = runs.findById(run.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(GlobalAssistantRunStatus.FAILED);
        assertThat(finished.errorCode()).isEqualTo("RUN_STEP_LIMIT");
        long toolStarts = events.findByRun(run.id()).stream()
                .filter(e -> e.type().equals("TOOL_STARTED")).count();
        assertThat(toolStarts).isEqualTo(5);
    }
}
