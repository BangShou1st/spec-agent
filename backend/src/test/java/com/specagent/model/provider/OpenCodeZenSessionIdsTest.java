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

    /**
     * 线上计数器门禁(2026-10-16 字节级 A/B 定位的 403 根因):降序会话 id 的
     * 中段三位十六进制必须落在 0xff0..0xfff(真实客户端"同一毫秒内从 0 递增"
     * 的计数器语义,经位取反后形成)。随机计数器只有 16/4096 的概率合规,
     * 上游对不合规 id 返回 403 FreeTierError。此处对一批连续生成的会话 id
     * 逐个断言该门禁形态——测试内的生成跨越毫秒边界也不会产生不合格 id。
     */
    @Test
    void sessionIdsCarryTheUpstreamCounterGateShape() {
        for (int i = 0; i < 64; i++) {
            String id = OpenCodeZenSessionIds.newEphemeral();
            String payload = id.substring("ses_".length());
            // 前 12 位十六进制是取反后的(时间戳*0x1000 + 小值计数器):
            // 中段三位即 0xfff - counter,counter 从 0 单调递增,必然 <= 0xF。
            String mid = payload.substring(9, 12);
            int counterValue = 0xFFF - Integer.parseInt(mid, 16);
            assertThat(counterValue)
                    .as("session id %s must carry a small monotonically increasing counter", id)
                    .isBetween(0, 0xF);
            assertThat(Integer.parseInt(payload.substring(0, 3), 16))
                    .as("descending prefix high bits must decrease with time")
                    .isPositive();
        }
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
