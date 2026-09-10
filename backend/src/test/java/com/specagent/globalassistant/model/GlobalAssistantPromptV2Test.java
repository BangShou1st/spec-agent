package com.specagent.globalassistant.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.specagent.globalassistant.context.GlobalAssistantContext;
import com.specagent.globalassistant.context.GlobalAssistantContextBuilder;
import com.specagent.globalassistant.conversation.GlobalAssistantConversationService;
import com.specagent.globalassistant.conversation.GlobalAssistantThread;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Prompt V2 shape tests: principles, not full-string snapshots.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GlobalAssistantPromptV2Test {
    @Autowired GlobalAssistantPromptRenderer renderer;
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired GlobalAssistantContextBuilder contextBuilder;

    private String systemPrompt() {
        GlobalAssistantThread thread = conversations.createThread();
        GlobalAssistantContext context = contextBuilder.build(thread.id(), "hello",
                new GlobalAssistantContextBuilder.UiRequest("PROJECTS", null));
        return renderer.render(context, List.of()).get(0).content();
    }

    @Test
    void promptVersionIsV2() {
        assertThat(GlobalAssistantPromptRenderer.PROMPT_VERSION).isEqualTo("v2");
    }

    @Test
    void promptContainsFourKinds() {
        String prompt = systemPrompt();
        assertThat(prompt).contains("TOOL");
        assertThat(prompt).contains("CLARIFY");
        assertThat(prompt).contains("NAVIGATE");
        assertThat(prompt).contains("FINAL");
        assertThat(prompt).contains("exactly one decision kind");
    }

    @Test
    void legacyDecisionFieldsAreAbsent() {
        String prompt = systemPrompt();
        assertThat(prompt).doesNotContain("statusText");
        assertThat(prompt).doesNotContain("requiresUserInput");
        assertThat(prompt).doesNotContain("done");
    }

    @Test
    void projectsFallbackIsProhibited() {
        String prompt = systemPrompt();
        assertThat(prompt).contains("PROJECTS is not a fallback for uncertainty");
    }

    @Test
    void hintsAreNonCanonical() {
        String prompt = systemPrompt();
        assertThat(prompt).contains("identity hints only");
        assertThat(prompt).contains("not canonical");
    }

    @Test
    void fakeActionProseIsForbidden() {
        String prompt = systemPrompt();
        assertThat(prompt).contains("Do not say you will search");
    }

    @Test
    void onePrimaryNextAction() {
        String prompt = systemPrompt();
        assertThat(prompt).contains("One primary next action");
    }

    @Test
    void noBenchmarkExamples() {
        String prompt = systemPrompt();
        assertThat(prompt).doesNotContain("Calendar");
        assertThat(prompt).doesNotContain("Zephyr");
        assertThat(prompt).doesNotContain("Ledger");
        assertThat(prompt).doesNotContain("Orchard");
        assertThat(prompt).doesNotContain("Quill");
    }

    @Test
    void noModelSpecificInstructions() {
        String prompt = systemPrompt();
        assertThat(prompt).doesNotContain("MiMo");
        assertThat(prompt).doesNotContain("mimo");
    }

    @Test
    void projectNavigationRequiresResourceId() {
        String prompt = systemPrompt();
        assertThat(prompt).contains("PROJECT requires");
        assertThat(prompt).contains("resourceId");
    }

    @Test
    void nonProjectNavigationForbidsResourceId() {
        String prompt = systemPrompt();
        assertThat(prompt).contains("must not include resourceId");
    }

    @Test
    void navigateAssistantTextIsOptional() {
        String prompt = systemPrompt();
        assertThat(prompt).contains("optional and may be omitted");
    }
}
