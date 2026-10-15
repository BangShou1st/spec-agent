package com.specagent.model.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件名:OpenCodeModelProtocolTest.java
 *
 * 测试目标:官方目录 {@code provider.npm} 包名到协议类型的映射。未知包名必须是
 * {@code UNKNOWN}——绝不猜测成 Chat(猜错会让请求打到不存在的端点)。
 */
class OpenCodeModelProtocolTest {

    @Test
    void mapsOfficialNpmPackagesToTheirNativeProtocol() {
        assertThat(OpenCodeModelProtocol.fromNpmPackage("@ai-sdk/openai-compatible"))
                .isEqualTo(OpenCodeModelProtocol.CHAT);
        assertThat(OpenCodeModelProtocol.fromNpmPackage("@ai-sdk/openai"))
                .isEqualTo(OpenCodeModelProtocol.RESPONSES);
        assertThat(OpenCodeModelProtocol.fromNpmPackage("@ai-sdk/anthropic"))
                .isEqualTo(OpenCodeModelProtocol.ANTHROPIC);
        assertThat(OpenCodeModelProtocol.fromNpmPackage("@ai-sdk/google"))
                .isEqualTo(OpenCodeModelProtocol.OTHER);
    }

    @Test
    void unknownOrMissingPackageIsNeverGuessedAsChat() {
        assertThat(OpenCodeModelProtocol.fromNpmPackage(null)).isEqualTo(OpenCodeModelProtocol.UNKNOWN);
        assertThat(OpenCodeModelProtocol.fromNpmPackage(" ")).isEqualTo(OpenCodeModelProtocol.UNKNOWN);
        assertThat(OpenCodeModelProtocol.fromNpmPackage("@ai-sdk/mystery")).isEqualTo(OpenCodeModelProtocol.OTHER);
    }

    @Test
    void onlyChatIsExecutableByTheImplementedCallChain() {
        assertThat(OpenCodeModelProtocol.CHAT.chatExecutable()).isTrue();
        for (OpenCodeModelProtocol protocol : new OpenCodeModelProtocol[]{
                OpenCodeModelProtocol.RESPONSES, OpenCodeModelProtocol.ANTHROPIC,
                OpenCodeModelProtocol.OTHER, OpenCodeModelProtocol.UNKNOWN}) {
            assertThat(protocol.chatExecutable()).as(protocol.name()).isFalse();
        }
    }
}
