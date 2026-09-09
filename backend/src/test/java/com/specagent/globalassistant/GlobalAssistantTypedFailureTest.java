package com.specagent.globalassistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.specagent.capability.CapabilityResult;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.globalassistant.conversation.GlobalAssistantConversationService;
import com.specagent.globalassistant.tool.ProjectGetSummaryCapability;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * FIX H/J RED: structured error codes, corrupt state fails closed.
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
