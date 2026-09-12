package com.specagent.globalassistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.globalassistant.context.GlobalAssistantContextBuilder;
import com.specagent.globalassistant.conversation.GlobalAssistantConversationService;
import com.specagent.globalassistant.conversation.GlobalAssistantRun;
import com.specagent.globalassistant.conversation.GlobalAssistantRunEvent;
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
import com.specagent.model.inference.ModelInferenceRequest;
import com.specagent.model.inference.ModelInferenceResponse;
import com.specagent.model.provider.FragmentListener;
import com.specagent.model.provider.StreamCancelledException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** True-streaming runtime semantics over a scripted fragment gateway. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GlobalAssistantStreamingIntegrationTest {

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
    @Autowired ObjectMapper mapper;

    /** Scripted fragment gateway: feeds fragments, records timing, optional hook/throw. */
    static final class ScriptedGateway implements ModelInferenceGateway {
        record Script(List<String> fragments, int hookAt, Runnable hook, RuntimeException throwAfter) {}
        final Deque<Script> scripts = new ArrayDeque<>();
        final List<Long> fragmentNanos = new ArrayList<>();
        long returnNanos = -1;

        @Override
        public ModelInferenceResponse complete(ModelInferenceRequest request) {
            return new ModelInferenceResponse("summary preserved", "stop", 0, 0);
        }

        @Override
        public ModelInferenceResponse completeStreaming(ModelInferenceRequest request, FragmentListener listener) {
            Script script = scripts.poll();
            StringBuilder full = new StringBuilder();
            for (int i = 0; i < script.fragments().size(); i++) {
                if (i == script.hookAt() && script.hook() != null) {
                    script.hook().run();
                }
                full.append(script.fragments().get(i));
                fragmentNanos.add(System.nanoTime());
                if (!listener.onFragment(script.fragments().get(i))) {
                    throw new StreamCancelledException("declined in test");
                }
            }
            if (script.throwAfter() != null) {
                throw script.throwAfter();
            }
            returnNanos = System.nanoTime();
            return new ModelInferenceResponse(full.toString(), "stop", 0, 0);
        }
    }

    private GlobalAssistantRuntime runtimeWith(ScriptedGateway gateway) {
        GlobalAssistantBrain brain = new GlobalAssistantBrain(renderer, gateway, parser, validator);
        return new GlobalAssistantRuntime(conversations, contextBuilder, brain, capabilities,
                runs, canonicalizer, budgets, lifecycle, runEvents, uiValidator, summaries);
    }

    private static GlobalAssistantThread newThread(GlobalAssistantConversationService conversations) {
        return conversations.createThread();
    }

    private List<GlobalAssistantRunEvent> answerDeltas(UUID runId) {
        return events.findByRun(runId).stream()
                .filter(e -> e.type().equals("ANSWER_DELTA"))
                .toList();
    }

    private String assistantText(UUID threadId) {
        return conversations.listMessages(threadId).stream()
                .filter(m -> m.role().name().equals("ASSISTANT"))
                .map(m -> m.content())
                .reduce((a, b) -> a + "|" + b).orElse("");
    }

    @Test
    void fragmentsEmittedBeforeProviderCompletes() {
        GlobalAssistantThread thread = newThread(conversations);
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        String p1 = "{\"kind\":\"FINAL\",\"assistantText\":\"Alpha. ";
        String p2 = "Upsilon phi chi psi omega aleph beth gimel daleth he waw zayin heth teth yod kaph lamed mem nun. ";
        String p3 = "TT end.\"}";
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.scripts.add(new ScriptedGateway.Script(List.of(p1, p2, p3), -1, null, null));
        runtimeWith(gateway).executeRun(thread.id(), run.id(), "tell me something",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.COMPLETED);
        List<GlobalAssistantRunEvent> deltas = answerDeltas(run.id());
        assertThat(deltas.size()).isGreaterThanOrEqualTo(3);
        assertThat(deltas.stream().map(GlobalAssistantRunEvent::sequence).toList())
                .isSorted();
        assertThat(deltas.stream().map(e -> e.payload().get("generation")).distinct().toList())
                .containsExactly(1);
        assertThat(gateway.fragmentNanos.get(0)).isLessThan(gateway.returnNanos);
        String full = p1 + p2 + p3;
        String expected = "Alpha. " + p2 + "TT end.";
        assertThat(assistantText(thread.id())).isEqualTo(expected);
        assertThat(assistantText(thread.id())).isEqualTo(assistantText(thread.id()));
    }

    @Test
    void toolTurnLeaksNoDraft() {
        GlobalAssistantThread thread = newThread(conversations);
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
         String toolA = "{\"toolRequest\":{\"capabilityId\":\"project.list_recent\",\"arguments\":{}}";
        String toolB = ",\"kind\":\"TOOL\"}";
        String fin = "{\"kind\":\"FINAL\",\"assistantText\":\"done here\"}";
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.scripts.add(new ScriptedGateway.Script(List.of(toolA, toolB), -1, null, null));
        gateway.scripts.add(new ScriptedGateway.Script(List.of(fin), -1, null, null));
        runtimeWith(gateway).executeRun(thread.id(), run.id(), "list recents",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.COMPLETED);
        List<GlobalAssistantRunEvent> all = events.findByRun(run.id());
        int toolDone = all.stream().filter(e -> e.type().equals("TOOL_COMPLETED")).mapToInt(GlobalAssistantRunEvent::sequence).findFirst().orElseThrow();
        assertThat(all.stream().filter(e -> e.type().equals("ANSWER_DELTA") || e.type().equals("ANSWER_STREAM_STARTED")).allMatch(e -> e.sequence() > toolDone)).isTrue();
        assertThat(assistantText(thread.id())).isEqualTo("done here");
    }

    @Test
    void clarifyStreamsQuestion() {
        GlobalAssistantThread thread = newThread(conversations);
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
         String a = "{\"kind\":\"CLARIFY\",\"assistantText\":\"Which project do you mean, alpha or beta? It matters a lot for the next step forward here. \"";
        String b = "}";
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.scripts.add(new ScriptedGateway.Script(List.of(a, b), -1, null, null));
        runtimeWith(gateway).executeRun(thread.id(), run.id(), "open it",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.COMPLETED);
        assertThat(answerDeltas(run.id()).size()).isGreaterThanOrEqualTo(1);
        assertThat(assistantText(thread.id())).contains("Which project");
    }

    @Test
    void navigateStreamsPerContract() {
        GlobalAssistantThread thread = newThread(conversations);
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        String nav = "{\"kind\":\"NAVIGATE\",\"assistantText\":\"opening now, hold on. \",\"uiAction\":{\"destination\":\"PROJECTS\"}}";
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.scripts.add(new ScriptedGateway.Script(List.of(nav.substring(0, 60), nav.substring(60)), -1, null, null));
        runtimeWith(gateway).executeRun(thread.id(), run.id(), "go to projects",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.COMPLETED);
        assertThat(answerDeltas(run.id()).size()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void repairReconcilesWithReset() {
        GlobalAssistantThread thread = newThread(conversations);
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        String bad = "{\"kind\":\"FINAL\",\"assistantText\":\"stale draft shown first here. \",\"bogus\":1}";
        String fix = "{\"kind\":\"FINAL\",\"assistantText\":\"fixed text stands alone here\"}";
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.scripts.add(new ScriptedGateway.Script(List.of(bad.substring(0, 40), bad.substring(40)), -1, null, null));
        gateway.scripts.add(new ScriptedGateway.Script(List.of(fix.substring(0, 30), fix.substring(30)), -1, null, null));
        runtimeWith(gateway).executeRun(thread.id(), run.id(), "answer me",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.COMPLETED);
        List<GlobalAssistantRunEvent> all = events.findByRun(run.id());
        assertThat(all.stream().filter(e -> e.type().equals("ANSWER_STREAM_RESET")).count()).isEqualTo(1);
        assertThat(all.stream().filter(e -> e.type().equals("ANSWER_DELTA")).map(e -> e.payload().get("generation")).distinct().sorted().toList())
                .containsExactly(1, 2);
        assertThat(assistantText(thread.id())).isEqualTo("fixed text stands alone here");
    }

    @Test
    void malformedRepairsWithoutVisibleDraft() {
        GlobalAssistantThread thread = newThread(conversations);
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.scripts.add(new ScriptedGateway.Script(List.of("{oops not json at all"), -1, null, null));
        gateway.scripts.add(new ScriptedGateway.Script(List.of("{\"kind\":\"FINAL\",\"assistantText\":\"recovered cleanly here\"}"), -1, null, null));
        runtimeWith(gateway).executeRun(thread.id(), run.id(), "answer me",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.COMPLETED);
        List<GlobalAssistantRunEvent> all = events.findByRun(run.id());
        assertThat(all.stream().noneMatch(e -> e.type().equals("ANSWER_STREAM_RESET"))).isTrue();
        assertThat(assistantText(thread.id())).isEqualTo("recovered cleanly here");
    }

    @Test
    void disconnectFailsWithoutPartialMessage() {
        GlobalAssistantThread thread = newThread(conversations);
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.scripts.add(new ScriptedGateway.Script(List.of("{\"kind\":\"FINAL\",\"assistantText\":\"partial"), -1, null, new RuntimeException("boom")));
        runtimeWith(gateway).executeRun(thread.id(), run.id(), "answer me",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.FAILED);
        assertThat(conversations.listMessages(thread.id()).stream()
                .filter(m -> m.role().name().equals("ASSISTANT")).count()).isEqualTo(1);
    }

    @Test
    void cancelMidStreamAbortsWithoutMessage() {
        GlobalAssistantThread thread = newThread(conversations);
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        String a = "{\"kind\":\"FINAL\",\"assistantText\":\"you will never see this streamed text at all here. ";
        String b = "}";
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.scripts.add(new ScriptedGateway.Script(List.of(a, b), 1, () -> runs.requestCancel(run.id()), null));
        runtimeWith(gateway).executeRun(thread.id(), run.id(), "answer me",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.CANCELLED);
        assertThat(conversations.listMessages(thread.id()).stream()
                .filter(m -> m.role().name().equals("ASSISTANT")).count()).isZero();
        assertThat(events.findByRun(run.id()).stream()
                .filter(e -> e.type().equals("ASSISTANT_DELTA")).count()).isZero();
    }

    @Test
    void toolTurnCancelledMidStreamExecutesNoTool() {
        GlobalAssistantThread thread = newThread(conversations);
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        String toolA = "{\"toolRequest\":{\"capabilityId\":\"project.list_recent\",\"arguments\":{}}}";
        String toolB = ",\"kind\":\"TOOL\"}";
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.scripts.add(new ScriptedGateway.Script(List.of(toolA, toolB), 1, () -> runs.requestCancel(run.id()), null));
        runtimeWith(gateway).executeRun(thread.id(), run.id(), "list recents",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.CANCELLED);
        List<GlobalAssistantRunEvent> all = events.findByRun(run.id());
        assertThat(all.stream().filter(e -> e.type().equals("TOOL_STARTED")).count()).isZero();
        assertThat(all.stream().filter(e -> e.type().equals("TOOL_COMPLETED")).count()).isZero();
        assertThat(all.stream().filter(e -> e.type().equals("TOOL_FAILED")).count()).isZero();
        assertThat(all.stream().filter(e -> e.type().equals("ANSWER_DELTA")
                || e.type().equals("ANSWER_STREAM_STARTED")).count()).isZero();
        assertThat(all.stream().filter(e -> e.type().equals("ASSISTANT_DELTA")).count()).isZero();
        assertThat(conversations.listMessages(thread.id()).stream()
                .filter(m -> m.role().name().equals("ASSISTANT")).count()).isZero();
    }

    @Test
    void finalCancelledBeforeFirstProseAbortsWithoutDraft() {
        GlobalAssistantThread thread = newThread(conversations);
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        String a = "{\"kind\":\"FIN";
        String b = "AL\",\"assistantText\":\"late prose here\"}";
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.scripts.add(new ScriptedGateway.Script(List.of(a, b), 1, () -> runs.requestCancel(run.id()), null));
        runtimeWith(gateway).executeRun(thread.id(), run.id(), "answer me",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.CANCELLED);
        List<GlobalAssistantRunEvent> all = events.findByRun(run.id());
        assertThat(all.stream().filter(e -> e.type().equals("ANSWER_DELTA")
                || e.type().equals("ANSWER_STREAM_STARTED")).count()).isZero();
        assertThat(conversations.listMessages(thread.id()).stream()
                .filter(m -> m.role().name().equals("ASSISTANT")).count()).isZero();
    }

    private static boolean deltaHasUnpairedSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)
                    && (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1)))) {
                return true;
            }
            if (Character.isLowSurrogate(c)
                    && (i == 0 || !Character.isHighSurrogate(s.charAt(i - 1)))) {
                return true;
            }
        }
        return false;
    }

    @Test
    void duplicateKindRejectsWithoutToolExecution() {
        GlobalAssistantThread thread = newThread(conversations);
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        String dup = "{\"kind\":\"FINAL\",\"assistantText\":\"stale\",\"kind\":\"TOOL\",\"toolRequest\":{\"capabilityId\":\"project.search\",\"arguments\":{\"query\":\"x\"}}}";
        String fix = "{\"kind\":\"FINAL\",\"assistantText\":\"recovered after dup kind\"}";
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.scripts.add(new ScriptedGateway.Script(List.of(dup.substring(0, 40), dup.substring(40)), -1, null, null));
        gateway.scripts.add(new ScriptedGateway.Script(List.of(fix), -1, null, null));
        runtimeWith(gateway).executeRun(thread.id(), run.id(), "search stuff",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.COMPLETED);
        List<GlobalAssistantRunEvent> all = events.findByRun(run.id());
        assertThat(all.stream().noneMatch(e -> e.type().equals("TOOL_STARTED")
                || e.type().equals("TOOL_COMPLETED") || e.type().equals("TOOL_FAILED"))).isTrue();
        assertThat(all.stream().filter(e -> e.type().equals("ANSWER_STREAM_RESET")).count()).isEqualTo(1);
        assertThat(all.stream().filter(e -> e.type().equals("ANSWER_DELTA")).map(e -> e.payload().get("generation")).distinct().sorted().toList())
                .containsExactly(1, 2);
        assertThat(assistantText(thread.id())).isEqualTo("recovered after dup kind");
        assertThat(conversations.listMessages(thread.id()).stream()
                .filter(m -> m.role().name().equals("ASSISTANT")).count()).isEqualTo(1);
    }

    @Test
    void duplicateAssistantTextRejectsWithoutLastWins() {
        GlobalAssistantThread thread = newThread(conversations);
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        String dup = "{\"kind\":\"FINAL\",\"assistantText\":\"wrong\",\"assistantText\":\"right\"}";
        String fix = "{\"kind\":\"FINAL\",\"assistantText\":\"exactly this\"}";
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.scripts.add(new ScriptedGateway.Script(List.of(dup.substring(0, 30), dup.substring(30)), -1, null, null));
        gateway.scripts.add(new ScriptedGateway.Script(List.of(fix), -1, null, null));
        runtimeWith(gateway).executeRun(thread.id(), run.id(), "tell me",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.COMPLETED);
        List<GlobalAssistantRunEvent> all = events.findByRun(run.id());
        assertThat(all.stream().filter(e -> e.type().equals("ANSWER_STREAM_RESET")).count()).isEqualTo(1);
        assertThat(assistantText(thread.id())).isEqualTo("exactly this");
        assertThat(conversations.listMessages(thread.id()).stream()
                .filter(m -> m.role().name().equals("ASSISTANT")).count()).isEqualTo(1);
    }

    @Test
    void emojiBoundaryDeltasPersistWithoutUnpairedSurrogates() {
        GlobalAssistantThread thread = newThread(conversations);
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        String p1 = "{\"kind\":\"FINAL\",\"assistantText\":\"hi \\uD83D";
        String p2 = "\\uDE00 bye\"}";
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.scripts.add(new ScriptedGateway.Script(List.of(p1, p2), -1, null, null));
        runtimeWith(gateway).executeRun(thread.id(), run.id(), "greet me",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.COMPLETED);
        List<GlobalAssistantRunEvent> deltas = answerDeltas(run.id());
        assertThat(deltas.size()).isGreaterThanOrEqualTo(1);
        for (GlobalAssistantRunEvent delta : deltas) {
            Object text = delta.payload().get("text");
            assertThat(deltaHasUnpairedSurrogate(String.valueOf(text))).isFalse();
        }
        assertThat(assistantText(thread.id())).isEqualTo("hi \uD83D\uDE00 bye");
    }

    @Test
    void malformedSurrogateRepairsWithResetAndSingleFinalMessage() {
        GlobalAssistantThread thread = newThread(conversations);
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        String bad = "{\"kind\":\"FINAL\",\"assistantText\":\"oops \\uD83DA\"}";
        String fix = "{\"kind\":\"FINAL\",\"assistantText\":\"clean recovery\"}";
        ScriptedGateway gateway = new ScriptedGateway();
        gateway.scripts.add(new ScriptedGateway.Script(List.of(bad.substring(0, 30), bad.substring(30)), -1, null, null));
        gateway.scripts.add(new ScriptedGateway.Script(List.of(fix), -1, null, null));
        runtimeWith(gateway).executeRun(thread.id(), run.id(), "greet me",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        assertThat(runs.findById(run.id()).orElseThrow().status())
                .isEqualTo(GlobalAssistantRunStatus.COMPLETED);
        List<GlobalAssistantRunEvent> all = events.findByRun(run.id());
        assertThat(all.stream().filter(e -> e.type().equals("ANSWER_STREAM_RESET")).count()).isEqualTo(1);
        for (GlobalAssistantRunEvent delta : all.stream()
                .filter(e -> e.type().equals("ANSWER_DELTA")).toList()) {
            assertThat(deltaHasUnpairedSurrogate(String.valueOf(delta.payload().get("text")))).isFalse();
        }
        assertThat(assistantText(thread.id())).isEqualTo("clean recovery");
        assertThat(conversations.listMessages(thread.id()).stream()
                .filter(m -> m.role().name().equals("ASSISTANT")).count()).isEqualTo(1);
    }
}
