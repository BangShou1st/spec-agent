package com.specagent.assistant;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.assistant.runtime.GlobalAssistantContextBuilder;
import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.conversation.GlobalAssistantRun;
import com.specagent.assistant.conversation.GlobalAssistantRunEventRepository;
import com.specagent.assistant.conversation.GlobalAssistantRunRepository;
import com.specagent.assistant.conversation.GlobalAssistantThread;
import com.specagent.assistant.model.GlobalAssistantBrain;
import com.specagent.assistant.model.GlobalAssistantDecisionParser;
import com.specagent.assistant.model.GlobalAssistantDecisionValidator;
import com.specagent.assistant.model.GlobalAssistantPromptRenderer;
import com.specagent.assistant.runtime.GlobalAssistantSummaryService;
import com.specagent.assistant.runtime.GlobalAssistantRuntime;
import com.specagent.assistant.runtime.GlobalAssistantRuntimeProperties;
import com.specagent.assistant.runtime.GlobalAssistantToolArgumentCanonicalizer;
import com.specagent.model.contract.ModelInferenceGateway;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelInferenceResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
/** Timing instrumentation must never swallow an uncaught Error from summaries. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GlobalAssistantSummaryErrorPropagationTest {
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
    @Autowired GlobalAssistantSummaryService summaries;
    @TestConfiguration
    static class ThrowingSummaries {
        @Bean
        @Primary
        GlobalAssistantSummaryService summaries() {
            return new GlobalAssistantSummaryService(null, null) {
                @Override
                public boolean maybeSummarize(UUID threadId, UUID runId) {
                    throw new AssertionError("summary-error-must-propagate");
                }
            };
        }
    }
    @Test
    void summaryErrorPropagatesInsteadOfBeingSwallowed() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantRun run = conversations.createRun(thread.id(), "v1", "v1", "fp");
        ModelInferenceGateway stub = (ModelInferenceRequest request) ->
                new ModelInferenceResponse("{\"kind\":\"FINAL\",\"assistantText\":\"ok\"}", "stop", 0, 0);
        GlobalAssistantBrain brain = new GlobalAssistantBrain(renderer, stub, parser, validator);
        GlobalAssistantRuntime runtime = new GlobalAssistantRuntime(conversations, contextBuilder, brain, capabilities,
                runs, canonicalizer, budgets, lifecycle, runEvents, uiValidator, summaries, modelTargets);
        assertThatThrownBy(() -> runtime.executeRun(thread.id(), run.id(), "hi",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("summary-error-must-propagate");
    }
}
