package com.specagent.assistant;

import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.conversation.GlobalAssistantRun;
import com.specagent.assistant.runtime.GaExecutionPreparation;
import com.specagent.model.provider.OpenCodeModelCatalog;
import com.specagent.model.provider.OpenCodeZenTransport;
import com.specagent.modelsettings.OpenCodeSettings;
import com.specagent.modelsettings.OpenCodeSettingsRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 文件名:OpenCodeModelProtocolOfflineMetadataGateTest.java
 *
 * 测试目标:元数据不可用时的失败关闭。测试档把官方目录元数据指向本机保留端口(连接
 * 必然失败),视图因此不可用——此时协议<b>无法确认</b>,运行期门禁绝不能把"无法确认"
 * 静默降级为 Chat。目录展示层仍保留既有可用列表兜底(不误伤),但运行期执行门禁拒绝。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpenCodeModelProtocolOfflineMetadataGateTest {

    @Autowired GlobalAssistantConversationService conversations;
    @Autowired GaExecutionPreparation preparation;
    @Autowired OpenCodeModelCatalog catalog;
    @Autowired JdbcTemplate jdbc;
    @MockBean OpenCodeSettingsRepository settings;
    @MockBean OpenCodeZenTransport transport;

    @Test
    void unavailableMetadataIsNeverSilentlyTreatedAsChatAtRuntime() {
        Instant now = Instant.now();
        when(settings.find()).thenReturn(Optional.of(
                new OpenCodeSettings("test-key-no-network", "work", "would-be-chat", now, now)));

        // 目录展示层保持既有兜底(元数据不可用时不误伤正在工作的模型)……
        assertThat(catalog.chatExecutable("would-be-chat")).isTrue();
        // ……但运行期执行门禁无法确认协议 → 失败关闭,绝不当成 Chat。
        assertThat(catalog.verifiedChatExecutable("would-be-chat")).isFalse();

        UUID thread = conversations.createThread().id();
        GlobalAssistantRun run = conversations.createRunWithUserMessage(
                thread, "离线元数据", "ga-test", "ga-test", "ga-test");

        assertThat(preparation.prepare(thread, run.id(), "离线元数据", null)).isNull();
        assertThat(jdbc.queryForObject("SELECT status FROM global_assistant_runs WHERE id=?", String.class, run.id()))
                .isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT error_code FROM global_assistant_runs WHERE id=?", String.class, run.id()))
                .isEqualTo("GA_MODEL_PROTOCOL_UNSUPPORTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ga_executions WHERE run_id=?", Integer.class, run.id()))
                .isZero();
        verifyNoInteractions(transport);
    }
}
