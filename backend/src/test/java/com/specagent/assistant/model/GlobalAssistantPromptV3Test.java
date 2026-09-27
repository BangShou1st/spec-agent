package com.specagent.assistant.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.specagent.assistant.model.GlobalAssistantContext;
import com.specagent.assistant.runtime.GlobalAssistantContextBuilder;
import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.conversation.GlobalAssistantThread;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件名:GlobalAssistantPromptV3Test.java
 *
 * 测试目标:提示词 V3 的形态测试——校验原则要点而非整串快照。
 * 覆盖场景:版本号为 v3、skill 事实必须经工具佐证、四种决策类型齐全、
 * 无遗留决策字段、禁止把 PROJECTS 当兜底导航、候选提示非规范化、
 * 禁止虚构动作话术、单一主下一步、不含基准测试示例与模型特定指令、
 * PROJECT 导航必须携带 resourceId 而其他目的地禁止携带、
 * NAVIGATE 的 assistantText 可选。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GlobalAssistantPromptV3Test {
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
    void promptVersionIsV3() {
        assertThat(GlobalAssistantPromptRenderer.PROMPT_VERSION).isEqualTo("v3");
    }

    @Test
    void skillFactsRequireToolGrounding() {
        String prompt = systemPrompt();
        assertThat(prompt).contains("such questions from memory");
        assertThat(prompt).contains("skill.import.discover");
        assertThat(prompt).contains("read-only and stages nothing");
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
