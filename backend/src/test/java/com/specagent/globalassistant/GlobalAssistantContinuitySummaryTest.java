package com.specagent.globalassistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.globalassistant.context.GlobalAssistantContext;
import com.specagent.globalassistant.context.GlobalAssistantContextBuilder;
import com.specagent.globalassistant.conversation.GlobalAssistantConversationService;
import com.specagent.globalassistant.conversation.GlobalAssistantRun;
import com.specagent.globalassistant.conversation.GlobalAssistantRunRepository;
import com.specagent.globalassistant.conversation.GlobalAssistantThread;
import com.specagent.globalassistant.conversation.GlobalAssistantWorkingState;
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
import com.specagent.model.inference.ModelInferenceRequest;
import com.specagent.model.inference.ModelInferenceResponse;
import com.specagent.project.Project;
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
 * FIX K/L/M RED: cross-run continuity, single current request, summary loop.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GlobalAssistantContinuitySummaryTest {
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired GlobalAssistantContextBuilder contextBuilder;
    @Autowired GlobalAssistantPromptRenderer renderer;
    @Autowired GlobalAssistantDecisionParser parser;
    @Autowired GlobalAssistantDecisionValidator validator;
    @Autowired CapabilityRuntime capabilities;
    @Autowired GlobalAssistantRunRepository runs;
    @Autowired GlobalAssistantToolArgumentCanonicalizer canonicalizer;
    @Autowired GlobalAssistantRuntimeProperties budgets;
    @Autowired GlobalAssistantRunLifecycleService lifecycle;
    @Autowired GlobalAssistantRunEventService runEvents;
    @Autowired GlobalAssistantUiActionValidator uiValidator;
    @Autowired ProjectService projects;
    @Autowired ObjectMapper mapper;
    private GlobalAssistantRuntime runtimeFor(Queue<String> scripts,
            GlobalAssistantSummaryService summaries) {
        ModelInferenceGateway stub = request -> new ModelInferenceResponse(scripts.poll(), "stop", 0, 0);
        GlobalAssistantBrain brain = new GlobalAssistantBrain(renderer, stub, parser, validator);
        return new GlobalAssistantRuntime(conversations, contextBuilder, brain, capabilities,
                runs, canonicalizer, budgets, lifecycle, runEvents, uiValidator, summaries);
    }
    private GlobalAssistantSummaryService noSummary() {
        return new GlobalAssistantSummaryService(conversations,
                new GlobalAssistantBrain(renderer,
                        request -> new ModelInferenceResponse("unused", "stop", 0, 0),
                        parser, validator)) {
            @Override
            public boolean maybeSummarize(UUID threadId, UUID runId) {
                return false;
            }
        };
    }
    @Test
    void currentRequestAppearsOnceInContext() {
        GlobalAssistantThread thread = conversations.createThread();
        conversations.appendUserMessage(thread.id(), "older question", null);
        GlobalAssistantRun run = conversations.createRunWithUserMessage(
                thread.id(), "current request text", "v1", "v1", "fp");
        GlobalAssistantContext context = contextBuilder.build(thread.id(), run.id(),
                "current request text",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(context.currentRequest()).isEqualTo("current request text");
        assertThat(context.recentConversation().stream()
                        .filter(turn -> turn.content().equals("current request text")))
                .isEmpty();
        assertThat(context.recentConversation().stream()
                        .anyMatch(turn -> turn.content().equals("older question")))
                .isTrue();
    }
    @Test
    void ambiguityAcrossRunsKeepsCandidatesAndWaitingFor() {
        Project first = projects.createProject("Continuity Alpha " + UUID.randomUUID());
        Project second = projects.createProject("Continuity Beta " + UUID.randomUUID());
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run1 = conversations.createRunWithUserMessage(
                thread.id(), "open the continuity project", "v1", "v1", "fp");
        String marker = first.title().substring(0, 12);
        Queue<String> scripts1 = new ArrayDeque<>(List.of(
                 "{\"kind\":\"TOOL\", \"toolRequest\":{\"capabilityId\":\"project.search\", \"arguments\":{\"query\":\"" + marker + "\"}}}",
                 "{ \"kind\":\"CLARIFY\", \"assistantText\":\"I found several candidates, which one did you mean?\"}"));
        runtimeFor(scripts1, noSummary()).executeRun(thread.id(), run1.id(), "open the continuity project",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        GlobalAssistantWorkingState state = conversations.readWorkingState(thread.id());
        assertThat(state.waitingFor()).isNotBlank();
        assertThat(state.candidateProjects()).isNotEmpty();
        GlobalAssistantRun run2 = conversations.createRunWithUserMessage(
                thread.id(), "the first one", "v1", "v1", "fp");
        GlobalAssistantContext context2 = contextBuilder.build(thread.id(), run2.id(), "the first one",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        String rendered = renderer.render(context2, List.of()).get(1).content();
        assertThat(rendered).contains(first.id().toString());
        assertThat(rendered).contains(state.waitingFor());
    }
    @Test
    void summaryProgressesOneChunkPerVersionWithoutOverlap() {
        AtomicInteger summaryCalls = new AtomicInteger();
        java.util.List<String> summaryInputs = new java.util.ArrayList<>();
        ModelInferenceGateway counting = (ModelInferenceRequest request) -> {
            if ("GLOBAL_ASSISTANT_SUMMARY".equals(request.callType())) {
                summaryCalls.incrementAndGet();
                summaryInputs.add(request.messages().get(request.messages().size() - 1).content());
                return new ModelInferenceResponse("Goals preserved across runs " + summaryCalls.get() + ".",
                        "stop", 0, 0);
            }
             return new ModelInferenceResponse("{\"kind\":\"FINAL\", \"assistantText\":\"ok\"}", "stop", 0, 0);
        };
        GlobalAssistantBrain brain = new GlobalAssistantBrain(renderer, counting, parser, validator);
        GlobalAssistantSummaryService summaries = new GlobalAssistantSummaryService(conversations, brain);
        GlobalAssistantThread thread = conversations.createThread();
        for (int i = 0; i < 30; i++) {
            conversations.appendUserMessage(thread.id(), "chunk talk " + i, null);
        }
        assertThat(summaries.maybeSummarize(thread.id(), UUID.randomUUID())).isFalse();
        assertThat(summaryCalls.get()).isEqualTo(0);
        for (int i = 30; i < 34; i++) {
            conversations.appendUserMessage(thread.id(), "chunk talk " + i, null);
        }
        // The cursor partitions the deterministic read order, which is the
        // only order the service may assume (same-millisecond rows tie on
        // created_at and fall back to id).
        java.util.List<String> order34 = conversations.listMessages(thread.id()).stream()
                .map(m -> m.content()).toList();
        assertThat(order34).hasSize(34);
        assertThat(summaries.maybeSummarize(thread.id(), UUID.randomUUID())).isTrue();
        assertThat(summaryCalls.get()).isEqualTo(1);
        assertThat(userLines(summaryInputs.get(0)))
                .isEqualTo(new java.util.HashSet<>(order34.subList(0, 10)));
        var afterFirst = conversations.findThread(thread.id()).orElseThrow();
        assertThat(afterFirst.summary()).contains("Goals preserved");
        assertThat(afterFirst.summaryVersion()).isEqualTo(1);
        GlobalAssistantContext later = contextBuilder.build(thread.id(), "follow-up",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(later.conversationSummary()).contains("Goals preserved");
        for (int i = 34; i < 44; i++) {
            conversations.appendUserMessage(thread.id(), "chunk talk " + i, null);
        }
        java.util.List<String> order44 = conversations.listMessages(thread.id()).stream()
                .map(m -> m.content()).toList();
        assertThat(summaries.maybeSummarize(thread.id(), UUID.randomUUID())).isTrue();
        assertThat(summaryCalls.get()).isEqualTo(2);
        assertThat(summaryInputs.get(1)).contains("Goals preserved across runs 1.");
        assertThat(userLines(summaryInputs.get(1)))
                .isEqualTo(new java.util.HashSet<>(order44.subList(10, 20)));
        assertThat(conversations.findThread(thread.id()).orElseThrow().summaryVersion()).isEqualTo(2);
        for (int i = 44; i < 49; i++) {
            conversations.appendUserMessage(thread.id(), "chunk talk " + i, null);
        }
        assertThat(summaries.maybeSummarize(thread.id(), UUID.randomUUID())).isFalse();
        assertThat(summaryCalls.get()).isEqualTo(2);
    }
    private static java.util.Set<String> userLines(String summaryInput) {
        java.util.Set<String> lines = new java.util.HashSet<>();
        for (String line : summaryInput.split("\n")) {
            if (line.startsWith("USER: ")) {
                lines.add(line.substring("USER: ".length()));
            }
        }
        return lines;
    }
}
