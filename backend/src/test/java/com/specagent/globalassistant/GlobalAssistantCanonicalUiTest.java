package com.specagent.globalassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

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
import com.specagent.globalassistant.model.GlobalAssistantSummaryService;
import com.specagent.globalassistant.runtime.GlobalAssistantRunLifecycleService;
import com.specagent.globalassistant.runtime.GlobalAssistantRuntime;
import com.specagent.globalassistant.runtime.GlobalAssistantRuntimeProperties;
import com.specagent.globalassistant.runtime.GlobalAssistantToolArgumentCanonicalizer;
import com.specagent.globalassistant.runtime.GlobalAssistantUiActionValidator;
import com.specagent.globalassistant.stream.GlobalAssistantRunEventService;
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
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * FIX C/G RED: canonical UI validation, no fabricated tool results.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GlobalAssistantCanonicalUiTest {
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
    private GlobalAssistantRuntime runtimeFor(Queue<String> scripts) {
        return runtimeWithCapabilities(scripts, capabilities);
    }
    private GlobalAssistantRuntime runtimeWithCapabilities(Queue<String> scripts,
            CapabilityRuntime runtime) {
        ModelInferenceGateway stub = request -> new ModelInferenceResponse(scripts.poll(), "stop", 0, 0);
        GlobalAssistantBrain brain = new GlobalAssistantBrain(renderer, stub, parser, validator);
        return new GlobalAssistantRuntime(conversations, contextBuilder, brain, runtime,
                runs, canonicalizer, budgets, lifecycle, runEvents, uiValidator, summaries);
    }
    @Test
    void randomProjectNavigationIsRejectedWithoutEmit() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRunWithUserMessage(
                thread.id(), "open it", "v1", "v1", "fp");
        String randomId = UUID.randomUUID().toString();
        Queue<String> scripts = new ArrayDeque<>(List.of(
                 "{\"kind\":\"NAVIGATE\", \"assistantText\":\"Opening.\", \"uiAction\":{\"destination\":\"PROJECT\", \"resourceId\":\"" + randomId + "\"}}"));
        runtimeFor(scripts).executeRun(thread.id(), run.id(), "open it",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        var finished = runs.findById(run.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(GlobalAssistantRunStatus.FAILED);
        assertThat(finished.errorCode()).isEqualTo("PROJECT_NOT_FOUND");
        assertThat(events.findByRun(run.id()).stream().anyMatch(e -> e.type().equals("UI_ACTION"))).isFalse();
    }
    @Test
    void existingProjectNavigationEmitsTypedAction() {
        Project project = projects.createProject("Canonical Nav " + UUID.randomUUID());
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRunWithUserMessage(
                thread.id(), "open it", "v1", "v1", "fp");
        Queue<String> scripts = new ArrayDeque<>(List.of(
                 "{\"kind\":\"NAVIGATE\", \"assistantText\":\"Opening.\", \"uiAction\":{\"destination\":\"PROJECT\", \"resourceId\":\"" + project.id() + "\"}}"));
        runtimeFor(scripts).executeRun(thread.id(), run.id(), "open it",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.COMPLETED);
        var uiAction = events.findByRun(run.id()).stream()
                .filter(e -> e.type().equals("UI_ACTION")).findFirst().orElseThrow();
        assertThat(String.valueOf(uiAction.payload().get("resourceId"))).isEqualTo(project.id().toString());
    }
    @Test
    void staleSelectedProjectIsNotProjectedAsTrusted() {
        GlobalAssistantThread thread = conversations.createThread();
        var context = contextBuilder.build(thread.id(), "open this",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS",
                        new GlobalAssistantContextBuilder.UiRequest.SelectedRef(
                                "PROJECT", UUID.randomUUID().toString())));
        assertThat(context.uiContext().selectedEntity()).isNull();
    }
    @Test
    void existingSelectedProjectIsProjected() {
        Project project = projects.createProject("Selected Hint " + UUID.randomUUID());
        GlobalAssistantThread thread = conversations.createThread();
        var context = contextBuilder.build(thread.id(), "open this",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS",
                        new GlobalAssistantContextBuilder.UiRequest.SelectedRef(
                                "PROJECT", project.id().toString())));
        assertThat(context.uiContext().selectedEntity()).isNotNull();
        assertThat(context.uiContext().selectedEntity().id()).isEqualTo(project.id().toString());
    }
    @Test
    void infrastructureFailureIsNeverAFabricatedResult() {
        CapabilityRuntime failing = Mockito.mock(CapabilityRuntime.class);
        when(failing.invokeApplicationScoped(anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("database unreachable"));
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRunWithUserMessage(
                thread.id(), "list them", "v1", "v1", "fp");
        Queue<String> scripts = new ArrayDeque<>(List.of(
                 "{\"kind\":\"TOOL\", \"toolRequest\":{\"capabilityId\":\"project.list_recent\", \"arguments\":{}}}"));
        runtimeWithCapabilities(scripts, failing).executeRun(thread.id(), run.id(), "list them",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        var finished = runs.findById(run.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(GlobalAssistantRunStatus.FAILED);
        assertThat(finished.errorCode()).isEqualTo("TOOL_EXECUTION_FAILED");
        assertThat(events.findByRun(run.id()).stream()
                        .filter(e -> e.type().equals("TOOL_FAILED"))
                        .anyMatch(e -> "TOOL_EXECUTION_FAILED".equals(String.valueOf(e.payload().get("errorCode")))))
                .isTrue();
        assertThat(events.findByRun(run.id()).stream().noneMatch(e -> e.type().equals("TOOL_COMPLETED")))
                .isTrue();
    }
    @Test
    void corruptWorkingStateFailsRunSanitized() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRunWithUserMessage(
                thread.id(), "hello", "v1", "v1", "fp");
        conversations.findThread(thread.id()).orElseThrow();
        org.springframework.jdbc.core.JdbcTemplate jdbc = applicationContextJdbc();
        jdbc.update("UPDATE global_assistant_threads SET working_state = CAST('\"broken\"' AS jsonb) WHERE id = ?",
                thread.id());
         Queue<String> scripts = new ArrayDeque<>(List.of("{\"kind\":\"FINAL\", \"assistantText\":\"Hi.\"}"));
        runtimeFor(scripts).executeRun(thread.id(), run.id(), "hello",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        var finished = runs.findById(run.id()).orElseThrow();
        assertThat(finished.status()).isEqualTo(GlobalAssistantRunStatus.FAILED);
        String allPayloads = events.findByRun(run.id()).stream()
                .map(e -> String.valueOf(e.payload())).collect(java.util.stream.Collectors.joining(" "));
        assertThat(allPayloads).doesNotContain("broken");
    }
    @Autowired org.springframework.context.ApplicationContext applicationContext;
    private org.springframework.jdbc.core.JdbcTemplate applicationContextJdbc() {
        return applicationContext.getBean(org.springframework.jdbc.core.JdbcTemplate.class);
    }
}
