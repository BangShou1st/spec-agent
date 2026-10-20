package com.specagent.modelsettings;

import com.specagent.model.contract.ModelProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 文件名: GeminiCustomProviderSettingsVerificationTest.java
 *
 * 用途: 官方 Custom API 接入 Google Gemini 模型的凭据安全保存与兼容性验证测试。
 * 遵循严格凭据保护规则:
 * 1. 运行时从本机桌面 key2.txt 动态读取密钥, 绝不将密钥写入代码、配置或日志;
 * 2. 通过官方 CustomProviderSettingsService 保存加密凭据, 绝不绕过服务直接 SQL 写入;
 * 3. 验证 CompatibilityProbeService 返回的有效 FINAL 决策, 严格计入推理预算 (1 次);
 * 4. 保持全局激活提供商不变 (仍为 KILO), 不擅自切换全局状态。
 */
@SpringBootTest
@ActiveProfiles("test")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "SPEC_AGENT_LIVE_GEMINI_TEST", matches = "true")
class GeminiCustomProviderSettingsVerificationTest {

    static {
        System.setProperty("https.proxyHost", "127.0.0.1");
        System.setProperty("https.proxyPort", "7897");
        System.setProperty("http.nonProxyHosts", "localhost|127.0.0.1");
    }

    private static final String GEMINI_OPENAI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai";
    private static final String PRIMARY_MODEL_ID = "gemini-3.8-flash";
    private static final String DISPLAY_NAME = "Google Gemini 3.8 Flash";

    @Autowired
    private CustomProviderSettingsService customService;

    @Autowired
    private ModelProviderSettingsService modelProviderSettingsService;

    @DynamicPropertySource
    static void configureTargetDatabase(DynamicPropertyRegistry registry) {
        String targetUrl = System.getenv().getOrDefault(
                "SPEC_AGENT_TARGET_DB_URL",
                "jdbc:postgresql://localhost:5434/spec_agent");
        registry.add("spring.datasource.url", () -> targetUrl);

        Path backendKeyFile = Path.of("E:/project/spec-agent/backend/data/secret-master.key");
        if (Files.exists(backendKeyFile)) {
            registry.add("spec.agent.secret.master-key", () -> {
                try {
                    return Files.readString(backendKeyFile).strip();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
        }
        Path rootKeyFile = Path.of("E:/project/spec-agent/data/secret-master.key");
        if (Files.exists(rootKeyFile)) {
            registry.add("spec.agent.secret.master-key-fallback", () -> {
                try {
                    return Files.readString(rootKeyFile).strip();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
        }
    }

    @Test
    void configureAndValidateGeminiViaOfficialCustomService() throws Exception {
        Path keyPath = Path.of(System.getProperty("user.home"), "Desktop", "key2.txt");
        assumeTrue(Files.exists(keyPath), "Desktop key2.txt must exist to run live verification");
        String apiKey = Files.readString(keyPath).strip();
        assumeTrue(!apiKey.isBlank(), "Desktop key2.txt must not be empty");

        // 1. Discover models using official interface (read-only models catalog, 0 inference cost)
        CustomProviderSettingsService.Discovery discovery =
                customService.discover("CHAT_COMPLETIONS", GEMINI_OPENAI_BASE_URL, apiKey);
        assertThat(discovery.manualModel()).isFalse();
        assertThat(discovery.models()).isNotEmpty();
        assertThat(discovery.models().stream().anyMatch(m -> m.contains(PRIMARY_MODEL_ID)))
                .as("Gemini 3.8 Flash must exist in official models catalog")
                .isTrue();

        // 2. Save settings via official service (encrypted via ModelCredentialCrypto, revision incremented)
        CustomProviderSettingsService.Status savedStatus = customService.save(
                "CHAT_COMPLETIONS",
                GEMINI_OPENAI_BASE_URL,
                apiKey,
                PRIMARY_MODEL_ID,
                "MANUAL",
                DISPLAY_NAME);
        assertThat(savedStatus.configured()).isTrue();
        assertThat(savedStatus.selectedModel()).isEqualTo(PRIMARY_MODEL_ID);
        assertThat(savedStatus.hasKey()).isTrue();
        assertThat(savedStatus.maskedKey()).isNotNull();

        // 3. Validate compatibility via official probe (1 inference call to Gemini 3.8 Flash)
        CustomProviderSettingsService.Status validatedStatus = customService.validate();
        assertThat(validatedStatus.validated()).isTrue();

        // 4. Verify global provider remains KILO
        assertThat(modelProviderSettingsService.activeProvider())
                .as("Active global provider must remain KILO")
                .isEqualTo(ModelProvider.KILO);
    }
}
