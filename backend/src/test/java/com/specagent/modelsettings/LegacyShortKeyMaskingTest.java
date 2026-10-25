package com.specagent.modelsettings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.provider.ProtocolAdapterRegistry;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * 文件名: LegacyShortKeyMaskingTest.java
 *
 * MS-CROSS-R1(R1-A):证明<b>历史持久化</b>的弱脱敏数据不再经 legacy Custom
 * 设置的状态边界泄漏。旧版逻辑把极短密钥(≤4 字符)原样存入 masked_suffix,
 * status() 直接信任该列,导致 maskedKey 变成 "••••abcd"(整密钥披露)。
 *
 * 本测试构造旧版数据库形态的行(apiKey 与 maskedSuffix 都是完整短密钥),
 * 经真实的 CustomProviderSettingsService.status()/save() 输出边界断言掩码,
 * 而不是只测 suffix() 辅助方法。
 */
class LegacyShortKeyMaskingTest {

    private final AtomicReference<CustomProviderSettings> stored = new AtomicReference<>();
    private final CustomProviderSettingsRepository repository = mock(CustomProviderSettingsRepository.class);

    private CustomProviderSettingsService service() {
        when(repository.find()).thenAnswer(i -> Optional.ofNullable(stored.get()));
        doAnswer(i -> {
            stored.set((CustomProviderSettings) i.getArgument(0));
            return null;
        }).when(repository).upsert(any());
        var service = new CustomProviderSettingsService(repository, new ObjectMapper(),
                mock(ProtocolAdapterRegistry.class),
                mock(com.specagent.model.provider.CompatibilityProbeService.class));
        return service;
    }

    /** 旧版行:apiKey 与 masked_suffix 均为完整密钥(弱脱敏时代的真实形态)。 */
    private static CustomProviderSettings legacyRow(String apiKey, String maskedSuffix) {
        return new CustomProviderSettings("CHAT_COMPLETIONS", "http://127.0.0.1:9/v1",
                apiKey, maskedSuffix, "model-a", "MANUAL", null, 3L, 3L,
                Instant.now(), Instant.now(), Instant.now());
    }

    @Test
    void legacyFourCharKeyIsNotExposedThroughStatus() {
        stored.set(legacyRow("abcd", "abcd"));
        var status = service().status();
        assertThat(status.hasKey()).isTrue();
        assertThat(status.maskedKey()).as("4-char legacy key must not be disclosed").isNull();
    }

    @Test
    void legacyThreeCharKeyIsNotExposedThroughStatus() {
        stored.set(legacyRow("abc", "abc"));
        assertThat(service().status().maskedKey()).isNull();
    }

    @Test
    void legacyNullKeyYieldsNullMaskAndHasKeyFalse() {
        stored.set(legacyRow(null, null));
        var status = service().status();
        assertThat(status.hasKey()).isFalse();
        assertThat(status.maskedKey()).isNull();
    }

    @Test
    void legacyBlankKeyYieldsNullMask() {
        stored.set(legacyRow("", null));
        var status = service().status();
        assertThat(status.maskedKey()).isNull();
    }

    @Test
    void longLegacyKeyShowsOnlyMaskedLastFour() {
        stored.set(legacyRow("fixture-long-secret", "cret"));
        var status = service().status();
        assertThat(status.maskedKey()).isEqualTo("••••cret");
        assertThat(status.maskedKey()).doesNotContain("fixture-long-se");
    }

    @Test
    void serializedStatusJsonNeverContainsTheLegacyShortKey() throws Exception {
        stored.set(legacyRow("abcd", "abcd"));
        String json = new ObjectMapper().writeValueAsString(service().status());
        assertThat(json).doesNotContain("abcd");
        assertThat(json).contains("\"maskedKey\":null");
    }

    @Test
    void savingUnchangedConfigurationKeepsRevisionAndMasksLeak() {
        stored.set(legacyRow("abcd", "abcd"));
        var service = service();
        // 用户不改 Key(apiKeyInput=null)、其余字段与已存一致:必须走内容一致短路,
        // 不写库、不递增修订号;status 输出仍按新逻辑脱敏。
        var status = service.save("CHAT_COMPLETIONS", "http://127.0.0.1:9/v1", null,
                "model-a", "MANUAL", null);
        assertThat(status.configRevision()).isEqualTo(3L);
        assertThat(status.maskedKey()).isNull();
        assertThat(stored.get().maskedSuffix()).isEqualTo("abcd"); // 未写库,历史行原样
        assertThat(stored.get().configRevision()).isEqualTo(3L);
    }

    @Test
    void savingOtherFieldsRewritesPersistedSuffixAndKeepsKey() {
        stored.set(legacyRow("abcd", "abcd"));
        var service = service();
        // 改 display name:legacy 单例的既有语义是内容变化即递增修订号并使验证
        // 失效(先于本轮的行为,不在此改变);本轮修复保证的是写库时 masked_suffix
        // 被重算为安全值,持久化的弱脱敏数据自然纠正。
        var status = service.save("CHAT_COMPLETIONS", "http://127.0.0.1:9/v1", null,
                "model-a", "MANUAL", "Renamed");
        assertThat(status.configRevision()).isEqualTo(4L);
        assertThat(status.maskedKey()).isNull();
        assertThat(stored.get().maskedSuffix()).isNull();
        assertThat(stored.get().apiKey()).isEqualTo("abcd");
    }

    @Test
    void longKeySaveWithoutRotationKeepsSafeSuffix() {
        stored.set(legacyRow("fixture-long-secret", "cret"));
        var status = service().save("CHAT_COMPLETIONS", "http://127.0.0.1:9/v1", null,
                "model-a", "MANUAL", null);
        assertThat(status.configRevision()).isEqualTo(3L);
        assertThat(status.maskedKey()).isEqualTo("••••cret");
    }
}
