package com.specagent.globalassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.globalassistant.api.GlobalAssistantApplicationService;
import com.specagent.globalassistant.context.GlobalAssistantContextBuilder;
import com.specagent.globalassistant.conversation.GlobalAssistantConversationService;
import com.specagent.globalassistant.conversation.GlobalAssistantMessage;
import com.specagent.globalassistant.conversation.GlobalAssistantRun;
import com.specagent.globalassistant.conversation.GlobalAssistantRunRepository;
import com.specagent.globalassistant.conversation.GlobalAssistantRunStatus;
import com.specagent.globalassistant.conversation.GlobalAssistantThread;
import com.specagent.globalassistant.model.GlobalAssistantBrain;
import com.specagent.globalassistant.model.GlobalAssistantDecisionParser;
import com.specagent.globalassistant.model.GlobalAssistantDecisionValidator;
import com.specagent.globalassistant.model.GlobalAssistantPromptRenderer;
import com.specagent.globalassistant.runtime.GlobalAssistantRunLifecycleService;
import com.specagent.globalassistant.runtime.GlobalAssistantRuntime;
import com.specagent.globalassistant.runtime.GlobalAssistantRuntimeProperties;
import com.specagent.globalassistant.runtime.GlobalAssistantToolArgumentCanonicalizer;
import com.specagent.globalassistant.stream.GlobalAssistantRunEventService;
import com.specagent.globalassistant.turn.PendingTurnRepository;
import com.specagent.globalassistant.turn.SteerPendingException;
import com.specagent.globalassistant.turn.TurnHandoffService;
import com.specagent.model.inference.ModelInferenceGateway;
import com.specagent.model.inference.ModelInferenceResponse;
import com.specagent.project.ProjectService;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GlobalAssistantFinalInteractionTest {
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
    @Autowired com.specagent.globalassistant.runtime.GlobalAssistantUiActionValidator uiValidator;
    @Autowired com.specagent.globalassistant.model.GlobalAssistantSummaryService summaries;
    @Autowired ProjectService projects;
    @Autowired ObjectMapper mapper;
    @Autowired TurnHandoffService handoff;
    @Autowired PendingTurnRepository pending;
    @Autowired GlobalAssistantApplicationService application;
    @Autowired com.specagent.globalassistant.turn.ThreadActivityService activity;
    @Autowired com.specagent.globalassistant.turn.ConversationDeleteService deletes;

    private GlobalAssistantRuntime runtimeWithScripts(Queue<String> scripts) {
        ModelInferenceGateway stub = request -> new ModelInferenceResponse(scripts.poll(), "stop", 0, 0);
        GlobalAssistantBrain brain = new GlobalAssistantBrain(renderer, stub, parser, validator);
        return new GlobalAssistantRuntime(conversations, contextBuilder, brain, capabilities, runs, canonicalizer, budgets, lifecycle, runEvents, uiValidator, summaries);
    }

    private GlobalAssistantContextBuilder.UiRequest ui() {
        return new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null);
    }

    @Test
    void cancelDuringModelCallPreventsFinalAnswer() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        ModelInferenceGateway stub = request -> {
            runs.requestCancel(run.id());
             return new ModelInferenceResponse("{\"assistantText\": \"should never commit\", \"kind\":\"FINAL\"}", "stop", 0, 0);
        };
        GlobalAssistantBrain brain = new GlobalAssistantBrain(renderer, stub, parser, validator);
        var runtime = new GlobalAssistantRuntime(conversations, contextBuilder, brain, capabilities, runs, canonicalizer, budgets, lifecycle, runEvents, uiValidator, summaries);
        runtime.executeRun(thread.id(), run.id(), "hello", ui());
        assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo(GlobalAssistantRunStatus.CANCELLED);
        assertThat(conversations.listMessages(thread.id()).stream().noneMatch(m -> "should never commit".equals(m.content()))).isTrue();
    }

    @Test
    void cancelBeforeClarificationPreventsCompletion() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        ModelInferenceGateway stub = request -> {
            runs.requestCancel(run.id());
             return new ModelInferenceResponse("{\"assistantText\": \"which project?\", \"kind\":\"CLARIFY\"}", "stop", 0, 0);
        };
        GlobalAssistantBrain brain = new GlobalAssistantBrain(renderer, stub, parser, validator);
        var runtime = new GlobalAssistantRuntime(conversations, contextBuilder, brain, capabilities, runs, canonicalizer, budgets, lifecycle, runEvents, uiValidator, summaries);
        runtime.executeRun(thread.id(), run.id(), "open", ui());
        assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo(GlobalAssistantRunStatus.CANCELLED);
    }

    @Test
    void cancelBeforeUiActionPreventsNavigation() {
        var project = projects.createProject("UI Cancel Target " + UUID.randomUUID());
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        ModelInferenceGateway stub = request -> {
            runs.requestCancel(run.id());
             return new ModelInferenceResponse("{\"assistantText\": \"Opening.\", \"uiAction\": {\"destination\": \"PROJECT\", \"resourceId\": \"" + project.id() + "\"}, \"kind\":\"NAVIGATE\"}", "stop", 0, 0);
        };
        GlobalAssistantBrain brain = new GlobalAssistantBrain(renderer, stub, parser, validator);
        var runtime = new GlobalAssistantRuntime(conversations, contextBuilder, brain, capabilities, runs, canonicalizer, budgets, lifecycle, runEvents, uiValidator, summaries);
        runtime.executeRun(thread.id(), run.id(), "open it", ui());
        assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo(GlobalAssistantRunStatus.CANCELLED);
    }

    @Test
    void toolCompletesDurablyThenRunStopsBeforeNextStep() {
        projects.createProject("Durable Tool " + UUID.randomUUID());
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRunWithUserMessage(thread.id(), "find", "v1", "v1", "fp");
        Queue<String> scripts = new ArrayDeque<>(List.of(
                 "{\"toolRequest\": {\"capabilityId\": \"project.list_recent\", \"arguments\": {}}, \"kind\":\"TOOL\"}",
                 "{\"assistantText\": \"done after tool\", \"kind\":\"FINAL\"}"));
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        ModelInferenceGateway stub = request -> {
            if (calls.incrementAndGet() == 2) {
                runs.requestCancel(run.id());
            }
            return new ModelInferenceResponse(scripts.poll(), "stop", 0, 0);
        };
        GlobalAssistantBrain brain = new GlobalAssistantBrain(renderer, stub, parser, validator);
        var runtime = new GlobalAssistantRuntime(conversations, contextBuilder, brain, capabilities, runs, canonicalizer, budgets, lifecycle, runEvents, uiValidator, summaries);
        runtime.executeRun(thread.id(), run.id(), "find", ui());
        assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo(GlobalAssistantRunStatus.CANCELLED);
        assertThat(runEvents.findByRun(run.id()).stream().anyMatch(e -> "TOOL_COMPLETED".equals(e.type()))).isTrue();
    }

    @Test
    void steerPersistsPendingAndCancelsActiveRun() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRunWithUserMessage(thread.id(), "first", "v1", "v1", "fp");
        var accepted = handoff.acceptSteer(thread.id(), run.id(), "换方向，直接创建新的", ui());
        assertThat(accepted.pendingTurn().message()).isEqualTo("换方向，直接创建新的");
        assertThat(accepted.successor()).isEmpty();
        assertThat(runs.findById(run.id()).orElseThrow().cancelRequestedAt()).isNotNull();
        assertThat(pending.findUnresolvedByThread(thread.id())).isPresent();
    }

    @Test
    void secondSteerWhilePendingIsRejected() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRunWithUserMessage(thread.id(), "first", "v1", "v1", "fp");
        handoff.acceptSteer(thread.id(), run.id(), "first steer", ui());
        assertThatThrownBy(() -> handoff.acceptSteer(thread.id(), run.id(), "second steer", ui())).isInstanceOf(SteerPendingException.class);
    }

    @Test
    void steerOnTerminalRunCreatesSuccessorDirectly() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRunWithUserMessage(thread.id(), "first", "v1", "v1", "fp");
        runs.requestCancel(run.id());
        lifecycle.cancelAndTerminalize(run.id());
        var accepted = handoff.acceptSteer(thread.id(), run.id(), "直接创建新的", ui());
        assertThat(accepted.successor()).isPresent();
        assertThat(accepted.successor().get().run().threadId()).isEqualTo(thread.id());
        long userCount = conversations.listMessages(thread.id()).stream().filter(m -> m.role() == GlobalAssistantMessage.Role.USER && "直接创建新的".equals(m.content())).count();
        assertThat(userCount).isEqualTo(1);
    }

    @Test
    void handoffAfterCancelCreatesExactlyOneSuccessor() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun runA = conversations.createRunWithUserMessage(thread.id(), "A", "v1", "v1", "fp");
        handoff.acceptSteer(thread.id(), runA.id(), "B requirement", ui());
        lifecycle.cancelAndTerminalize(runA.id());
        var s1 = handoff.tryHandoff(thread.id());
        assertThat(s1).isPresent();
        var s2 = handoff.tryHandoff(thread.id());
        assertThat(s2).isEmpty();
        long count = conversations.listMessages(thread.id()).stream().filter(m -> "B requirement".equals(m.content())).count();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void handoffAfterCompletedOrFailedStillCreatesSuccessor() {
        for (String terminal : List.of("COMPLETED", "FAILED")) {
            GlobalAssistantThread thread = conversations.createThread();
            GlobalAssistantRun runA = conversations.createRunWithUserMessage(thread.id(), "A", "v1", "v1", "fp");
            handoff.acceptSteer(thread.id(), runA.id(), "steer-" + terminal, ui());
            if ("COMPLETED".equals(terminal)) {
                lifecycle.completeWithAssistant(thread.id(), runA.id(), "done");
            } else {
                lifecycle.failWithAssistant(thread.id(), runA.id(), "oops", "TOOL_EXECUTION_FAILED", "x");
            }
            var s = handoff.tryHandoff(thread.id());
            assertThat(s).isPresent();
        }
    }

    @Test
    void threadStopDiscardsPendingAndCancelsActive() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRunWithUserMessage(thread.id(), "first", "v1", "v1", "fp");
        handoff.acceptSteer(thread.id(), run.id(), "steer me", ui());
        var act = application.stopThread(thread.id());
        assertThat(act.pendingSteer()).isEmpty();
        assertThat(runs.findById(run.id()).orElseThrow().cancelRequestedAt()).isNotNull();
        assertThat(handoff.tryHandoff(thread.id())).isEmpty();
    }

    @Test
    void threadStopIdleIsIdempotent() {
        GlobalAssistantThread thread = conversations.createThread();
        var a1 = application.stopThread(thread.id());
        var a2 = application.stopThread(thread.id());
        assertThat(a1.activeRun()).isEmpty();
        assertThat(a2.activeRun()).isEmpty();
    }

    @Test
    void activityTruthCoversIdleActivePending() {
        GlobalAssistantThread thread = conversations.createThread();
        assertThat(activity.read(thread.id()).activeRun()).isEmpty();
        GlobalAssistantRun run = conversations.createRunWithUserMessage(thread.id(), "hi", "v1", "v1", "fp");
        assertThat(activity.read(thread.id()).activeRun()).isPresent();
        handoff.acceptSteer(thread.id(), run.id(), "steer", ui());
        var act = activity.read(thread.id());
        assertThat(act.activeRun()).isPresent();
        assertThat(act.pendingSteer()).isPresent();
    }

    @Test
    void deleteIdleThreadRemovesConversationButPreservesProject() {
        var project = projects.createProject("Keep Me " + UUID.randomUUID());
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRunWithUserMessage(thread.id(), "make", "v1", "v1", "fp");
        lifecycle.completeWithAssistant(thread.id(), run.id(), "done");
        application.deleteThread(thread.id());
        assertThat(conversations.findThread(thread.id())).isEmpty();
        assertThat(projects.getProject(project.id())).isPresent();
    }

    @Test
    void deleteActiveThreadIsRejected() {
        GlobalAssistantThread thread = conversations.createThread();
        conversations.createRunWithUserMessage(thread.id(), "running", "v1", "v1", "fp");
        assertThatThrownBy(() -> application.deleteThread(thread.id()))
                .isInstanceOf(com.specagent.api.common.ApiException.class)
                .matches(ex -> "GLOBAL_ASSISTANT_THREAD_ACTIVE".equals(((com.specagent.api.common.ApiException) ex).code()));
    }

    @Test
    void singleUnresolvedSteerEnforcedByDb() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRunWithUserMessage(thread.id(), "first", "v1", "v1", "fp");
        pending.insert(thread.id(), run.id(), "one", "{}");
        assertThatThrownBy(() -> pending.insert(thread.id(), run.id(), "two", "{}")).isInstanceOf(SteerPendingException.class);
    }
}
