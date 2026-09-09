package com.specagent.globalassistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.specagent.globalassistant.conversation.GlobalAssistantConversationService;
import com.specagent.globalassistant.conversation.GlobalAssistantThread;
import com.specagent.globalassistant.conversation.GlobalAssistantThreadListItem;
import com.specagent.globalassistant.api.GlobalAssistantController;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Conversation Library read-model: deterministic title/preview/recency, max 50, no empty threads.
 * RED-first behavioral coverage for GET /threads backing projection.
 */
@SpringBootTest
@ActiveProfiles("test")
class GlobalAssistantConversationLibraryTest {
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired GlobalAssistantController controller;

    @org.junit.jupiter.api.AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM global_assistant_run_events");
        jdbc.update("DELETE FROM global_assistant_runs");
        jdbc.update("DELETE FROM global_assistant_messages");
        jdbc.update("DELETE FROM global_assistant_threads");
    }

    @Test
    void emptyThreadIsNotReturned() {
        conversations.createThread();
        List<GlobalAssistantThreadListItem> items = conversations.listThreads();
        assertThat(items).isEmpty();
    }

    @Test
    void firstUserMessageBecomesTitle() {
        GlobalAssistantThread thread = conversations.createThread();
        conversations.appendUserMessage(thread.id(), "帮我找之前的支付项目", null);
        conversations.appendAssistantMessage(thread.id(), "找到了支付结算系统", null);
        List<GlobalAssistantThreadListItem> items = conversations.listThreads();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("帮我找之前的支付项目");
    }

    @Test
    void firstAssistantCannotBecomeTitle() {
        GlobalAssistantThread thread = conversations.createThread();
        conversations.appendAssistantMessage(thread.id(), "我是助手先说话", null);
        conversations.appendUserMessage(thread.id(), "真正的首条用户问题", null);
        List<GlobalAssistantThreadListItem> items = conversations.listThreads();
        assertThat(items).hasSize(1);
        assertThat(items.get(0).title()).isEqualTo("真正的首条用户问题");
    }

    @Test
    void latestMessageBecomesPreview() {
        GlobalAssistantThread thread = conversations.createThread();
        conversations.appendUserMessage(thread.id(), "first question", null);
        conversations.appendAssistantMessage(thread.id(), "找到了支付结算系统，目前有三个候选", null);
        List<GlobalAssistantThreadListItem> items = conversations.listThreads();
        assertThat(items.get(0).preview()).contains("找到了支付结算系统");
    }

    @Test
    void laterMessagesDoNotChangeTitle() {
        GlobalAssistantThread thread = conversations.createThread();
        conversations.appendUserMessage(thread.id(), "原始标题问题", null);
        conversations.appendUserMessage(thread.id(), "后续追问完全不同", null);
        conversations.appendAssistantMessage(thread.id(), "后续回答", null);
        List<GlobalAssistantThreadListItem> items = conversations.listThreads();
        assertThat(items.get(0).title()).isEqualTo("原始标题问题");
    }

    @Test
    void latestMessageControlsUpdatedAtAndRecency() throws Exception {
        GlobalAssistantThread older = conversations.createThread();
        conversations.appendUserMessage(older.id(), "older thread", null);
        Thread.sleep(20);
        GlobalAssistantThread newer = conversations.createThread();
        conversations.appendUserMessage(newer.id(), "newer thread", null);
        List<GlobalAssistantThreadListItem> items = conversations.listThreads();
        assertThat(items).hasSize(2);
        assertThat(items.get(0).threadId()).isEqualTo(newer.id());
        assertThat(items.get(1).threadId()).isEqualTo(older.id());
        assertThat(items.get(0).updatedAt()).isAfterOrEqualTo(items.get(1).updatedAt());
    }

    @Test
    void titleTruncationAddsEllipsis() {
        GlobalAssistantThread thread = conversations.createThread();
        String longTitle = "这是一个非常长的问题标题用于测试截断逻辑是否正确工作超过四十八个字符应该被截断并添加省略号结尾部分";
        conversations.appendUserMessage(thread.id(), longTitle, null);
        List<GlobalAssistantThreadListItem> items = conversations.listThreads();
        String title = items.get(0).title();
        assertThat(title.endsWith("…")).isTrue();
        assertThat(title.codePointCount(0, title.length())).isLessThanOrEqualTo(48);
    }

    @Test
    void previewTruncationAddsEllipsis() {
        GlobalAssistantThread thread = conversations.createThread();
        conversations.appendUserMessage(thread.id(), "short title", null);
        String longPreview = "这是一段非常长的助手回答内容，用于验证预览截断逻辑是否正确工作。当内容超过九十六个可见字符时应该被截断并以省略号结尾，后面这些文字不应该出现在预览结果当中，继续添加更多文字确保超长，再补三十个字符让它一定超过九十六个可见字符的上限要求。";
        conversations.appendAssistantMessage(thread.id(), longPreview, null);
        List<GlobalAssistantThreadListItem> items = conversations.listThreads();
        String preview = items.get(0).preview();
        assertThat(preview.endsWith("…")).isTrue();
        assertThat(preview.codePointCount(0, preview.length())).isLessThanOrEqualTo(96);
    }

    @Test
    void whitespaceIsNormalized() {
        GlobalAssistantThread thread = conversations.createThread();
        conversations.appendUserMessage(thread.id(), "  帮我找   支付项目  ", null);
        List<GlobalAssistantThreadListItem> items = conversations.listThreads();
        assertThat(items.get(0).title()).isEqualTo("帮我找 支付项目");
        assertThat(items.get(0).title()).doesNotContain("\n");
    }

    @Test
    void maxFiftyThreads() {
        for (int i = 0; i < 55; i++) {
            GlobalAssistantThread thread = conversations.createThread();
            conversations.appendUserMessage(thread.id(), "thread-" + i, null);
        }
        List<GlobalAssistantThreadListItem> items = conversations.listThreads();
        assertThat(items).hasSize(50);
    }

    @Test
    void assistantOnlyThreadIsNotReturned() {
        GlobalAssistantThread thread = conversations.createThread();
        conversations.appendAssistantMessage(thread.id(), "只有助手消息", null);
        List<GlobalAssistantThreadListItem> items = conversations.listThreads();
        assertThat(items).isEmpty();
    }

    @Test
    void tieOrderingIsDeterministic() {
        GlobalAssistantThread first = conversations.createThread();
        conversations.appendUserMessage(first.id(), "tie thread one", null);
        GlobalAssistantThread second = conversations.createThread();
        conversations.appendUserMessage(second.id(), "tie thread two", null);
        // Force identical latest-message timestamps so only the tie-break decides order.
        java.sql.Timestamp fixed = java.sql.Timestamp.from(java.time.Instant.parse("2026-09-10T00:00:00Z"));
        jdbc.update("UPDATE global_assistant_messages SET created_at = ?", fixed);
        List<GlobalAssistantThreadListItem> runOne = conversations.listThreads();
        List<GlobalAssistantThreadListItem> runTwo = conversations.listThreads();
        assertThat(runOne).hasSize(2);
        assertThat(runTwo).hasSize(2);
        assertThat(runOne.get(0).threadId()).isEqualTo(runTwo.get(0).threadId());
        assertThat(runOne.get(1).threadId()).isEqualTo(runTwo.get(1).threadId());
        // Deterministic tie-break: same input order yields same output order and covers both threads.
        assertThat(java.util.Set.of(runOne.get(0).threadId(), runOne.get(1).threadId()))
                .containsExactlyInAnyOrder(first.id(), second.id());
    }

    @Test
    void getThreadsContractReturnsProjectedList() {
        GlobalAssistantThread thread = conversations.createThread();
        conversations.appendUserMessage(thread.id(), "契约标题检查", null);
        conversations.appendAssistantMessage(thread.id(), "契约预览检查", null);
        List<GlobalAssistantController.ThreadListItemResponse> dto = controller.listThreads();
        assertThat(dto).hasSize(1);
        assertThat(dto.get(0).threadId()).isEqualTo(thread.id().toString());
        assertThat(dto.get(0).title()).isEqualTo("契约标题检查");
        assertThat(dto.get(0).preview()).contains("契约预览检查");
        assertThat(dto.get(0).updatedAt()).isNotBlank();
        assertThat(dto.get(0).createdAt()).isNotBlank();
    }
}
