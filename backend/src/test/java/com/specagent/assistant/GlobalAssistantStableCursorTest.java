package com.specagent.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import com.specagent.assistant.runtime.GaHostContext;
import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.conversation.GlobalAssistantMessage;
import com.specagent.assistant.conversation.GlobalAssistantThread;
import com.specagent.assistant.runtime.GaHostContext;
import com.specagent.model.contract.ModelInferenceGateway;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelInferenceResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件名:GlobalAssistantStableCursorTest.java
 *
 * 测试目标:摘要游标的稳定性——消息序号追加单调、时间戳并列时
 * 分块仍互不重叠、剩余消息进入上下文的边界正确。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GlobalAssistantStableCursorTest {
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired JdbcTemplate jdbc;
    private void tieAllTimestamps(UUID threadId) {
        jdbc.update("UPDATE global_assistant_messages SET created_at = TIMESTAMP '2026-01-01 00:00:00' WHERE thread_id = ?",
                threadId);
    }
    private List<String> contents(UUID threadId) {
        return conversations.listMessages(threadId).stream().map(GlobalAssistantMessage::content).toList();
    }
    @Test
    void laterAppendsWithTiedTimestampsKeepCanonicalOrder() {
        GlobalAssistantThread thread = conversations.createThread();
        for (int i = 0; i < 5; i++) {
            conversations.appendUserMessage(thread.id(), "order-" + i, null);
        }
        tieAllTimestamps(thread.id());
        for (int i = 5; i < 8; i++) {
            conversations.appendUserMessage(thread.id(), "order-" + i, null);
        }
        tieAllTimestamps(thread.id());
        assertThat(contents(thread.id())).containsExactly(
                "order-0", "order-1", "order-2", "order-3", "order-4",
                "order-5", "order-6", "order-7");
    }
}
