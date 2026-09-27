package com.specagent.model.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 文件名:OpenCodeZenSessionIdsTest.java
 *
 * 测试目标:验证会话 ID 的线上报文契约(并非格式美化):Zen 对 UUID 形状的
 * 会话 ID 返回 403 FreeTierError,对客户端形状返回 200(已通过与线上免费模型的
 * 单变量 A/B 对照验证)。覆盖会话 ID 的稳定性与客户端形状、null 会话 ID fail-closed、
 * 以及临时会话 ID 非空、单行、唯一且符合客户端形状。
 */
class OpenCodeZenSessionIdsTest {

    /** 12 个十六进制时间前缀字符 + 14 个 base62 字符。 */
    private static final String CLIENT_SHAPE = "[0-9a-f]{12}[0-9A-Za-z]{14}";

    @Test
    void conversationSessionIsStableAndCarriesTheClientShape() {
        UUID projectId = UUID.fromString("12345678-1234-1234-1234-123456789abc");

        String first = OpenCodeZenSessionIds.forConversation(projectId);
        String second = OpenCodeZenSessionIds.forConversation(projectId);

        // 一个项目固定对应一个 Provider 会话,项目内每个请求都延续同一会话而不是新开。
        assertThat(first).isEqualTo(second);
        assertThat(first).startsWith("ses_");
        assertThat(first.substring("ses_".length())).hasSize(26).matches(CLIENT_SHAPE);
        assertThat(OpenCodeZenSessionIds.forConversation(UUID.randomUUID())).isNotEqualTo(first);
    }

    @Test
    void nullConversationIdFailsClosed() {
        assertThatThrownBy(() -> OpenCodeZenSessionIds.forConversation(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ephemeralSessionsAreNonBlankSingleLineUniqueAndClientShaped() {
        String first = OpenCodeZenSessionIds.newEphemeral();
        String second = OpenCodeZenSessionIds.newEphemeral();

        assertThat(first).isNotBlank().startsWith("ses_");
        assertThat(first).doesNotContain("\n", "\r");
        assertThat(first.substring("ses_".length())).hasSize(26).matches(CLIENT_SHAPE);
        assertThat(second).isNotBlank().startsWith("ses_");
        assertThat(second).isNotEqualTo(first);
    }
}
