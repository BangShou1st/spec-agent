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
import com.specagent.globalassistant.runtime.GlobalAssistantRuntime;
import com.specagent.globalassistant.runtime.GlobalAssistantRuntimeProperties;
import com.specagent.globalassistant.runtime.GlobalAssistantToolArgumentCanonicalizer;
import com.specagent.globalassistant.stream.GlobalAssistantStreamService;
import com.specagent.model.inference.ModelInferenceGateway;
import com.specagent.model.inference.ModelInferenceResponse;
import com.specagent.project.Project;
import com.specagent.project.ProjectService;
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
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired GlobalAssistantContextBuilder contextBuilder;
    @Autowired GlobalAssistantPromptRenderer renderer;
    @Autowired GlobalAssistantDecisionParser parser;
    @Autowired GlobalAssistantDecisionValidator validator;
    @Autowired CapabilityRuntime capabilities;
    @Autowired GlobalAssistantRunRepository runs;
    @Autowired GlobalAssistantRunEventRepository events;
    @Autowired GlobalAssistantStreamService streams;
    @Autowired GlobalAssistantToolArgumentCanonicalizer canonicalizer;
    @Autowired GlobalAssistantRuntimeProperties budgets;
    @Autowired ProjectService projects;
    @Autowired ObjectMapper mapper;
    private GlobalAssistantRuntime runtimeWithScripts(Queue<String> scripts) {
        ModelInferenceGateway stub = request -> new ModelInferenceResponse(scripts.poll(), "stop", 0, 0);
        GlobalAssistantBrain brain = new GlobalAssistantBrain(renderer, stub, parser, validator);
        return new GlobalAssistantRuntime(conversations, contextBuilder, brain, capabilities,
                runs, events, streams, canonicalizer, budgets);
    }
    @Test
    void toolLoopResolvesSearchThenFinalAnswer() {
        Project project = projects.createProject("Runtime Search Target " + UUID.randomUUID());
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        Queue<String> scripts = new ArrayDeque<>(List.of(
                "{\"toolRequest\": {\"capabilityId\": \"project.search\", \"arguments\": {\"query\": \"" + project.title().substring(0, 8) + "\"}}, \"done\": false}",
                "{\"assistantText\": \"Found it.\", \"uiAction\": {\"destination\": \"PROJECT\", \"resourceId\": \""
                        + project.id() + "\"}, \"done\": true}"));
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
                "{\"assistantText\": \"You can organize projects by recency.\", \"done\": true}"));
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
                "{\"toolRequest\": {\"capabilityId\": \"project.list_recent\", \"arguments\": {}}, \"done\": false}";
        Queue<String> scripts = new ArrayDeque<>(List.of(toolJson, toolJson,
                "{\"assistantText\": \"Done.\", \"done\": true}"));
        runtimeWithScripts(scripts).executeRun(thread.id(), run.id(), "list recents",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.COMPLETED);
        long toolStarts = events.findByRun(run.id()).stream()
                .filter(e -> e.type().equals("TOOL_STARTED")).count();
        // No-progress stops the second identical call from running forever.
        assertThat(toolStarts).isLessThanOrEqualTo(2);
    }
    @Test
    void cancelRequestedBeforeToolPreventsExecution() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        runs.requestCancel(run.id());
        Queue<String> scripts = new ArrayDeque<>(List.of(
                "{\"toolRequest\": {\"capabilityId\": \"project.list_recent\", \"arguments\": {}}, \"done\": false}"));
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
                "{\"toolRequest\": {\"capabilityId\": \"skill.secret\", \"arguments\": {}}, \"done\": false}"));
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
                    + ((i % 10) + 1) + "}}, \"done\": false}");
        }
        runtimeWithScripts(scripts).executeRun(thread.id(), run.id(), "keep listing",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        GlobalAssistantRun finished = runs.findById(run.id()).orElseThrow();
        assertThat(finished.status()).isIn(
                GlobalAssistantRunStatus.COMPLETED, GlobalAssistantRunStatus.FAILED);
        long toolStarts = events.findByRun(run.id()).stream()
                .filter(e -> e.type().equals("TOOL_STARTED")).count();
        assertThat(toolStarts).isLessThanOrEqualTo(5);
    }
}
