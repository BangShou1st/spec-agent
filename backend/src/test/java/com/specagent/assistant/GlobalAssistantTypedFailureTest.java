package com.specagent.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.specagent.capability.CapabilityResult;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.tool.ProjectGetSummaryCapability;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * 文件名:GlobalAssistantTypedFailureTest.java
 *
 * 测试目标:验证失败是类型化的、状态损坏时失败收场。
 * 覆盖场景:未知项目返回结构化的 PROJECT_NOT_FOUND 错误码、
 * 工作状态 JSON 损坏时抛出异常而不是返回空状态。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GlobalAssistantTypedFailureTest {
    @Autowired CapabilityRuntime capabilities;
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired JdbcTemplate jdbc;
    @Test
    void unknownProjectCarriesStructuredNotFoundCode() {
        CapabilityResult result = capabilities.invokeApplicationScoped(
                "ga-typed-" + UUID.randomUUID(), ProjectGetSummaryCapability.CAPABILITY_ID, null,
                Map.of("projectId", UUID.randomUUID().toString()));
        assertThat(result.status()).isEqualTo(CapabilityResult.Status.FAILED);
        assertThat(String.valueOf(result.content().get("errorCode")))
                .isEqualTo("PROJECT_NOT_FOUND");
    }
    @Test
    void corruptWorkingStateFailsClosedInsteadOfEmpty() {
        var thread = conversations.createThread();
        jdbc.update("UPDATE global_assistant_threads SET working_state = CAST('\"just a string\"' AS jsonb) WHERE id = ?",
                thread.id());
        assertThatThrownBy(() -> conversations.readWorkingState(thread.id()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("corrupt");
    }
}
