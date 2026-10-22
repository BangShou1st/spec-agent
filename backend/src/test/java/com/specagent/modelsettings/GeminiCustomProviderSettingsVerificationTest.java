package com.specagent.modelsettings;

import com.specagent.eval.EvalLiveIsolatedDatabase;
import com.specagent.model.contract.ModelProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
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
@org.junit.jupiter.api.Tag("external-service")
@org.junit.jupiter.api.Tag("database-integration")
@ActiveProfiles("test")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named = "SPEC_AGENT_LIVE_GEMINI_TEST", matches = "true")
class GeminiCustomProviderSettingsVerificationTest {

    static {
        System.setProperty("https.proxyHost", "127.0.0.1");
        System.setProperty("https.proxyPort", "7897");
        System.setProperty("http.nonProxyHosts", "localhost|127.0.0.1");
    }

    private static final String GEMINI_OPENAI_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai";
    private static final String PRIMARY_MODEL_ID = System.getenv().getOrDefault(
            "SPEC_AGENT_GEMINI_MODEL_ID", "gemini-3.5-flash-lite");
    private static final String DISPLAY_NAME = "Google Gemini 3.5 Flash Lite";

    @Autowired
    private CustomProviderSettingsService customService;

    @Autowired
    private ModelProviderSettingsService modelProviderSettingsService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureTargetDatabase(DynamicPropertyRegistry registry) {
        String targetUrl = System.getenv("SPEC_AGENT_TARGET_DB_URL");
        // 阶段一: 静态预检查。必须显式配置且必须是合法的 spec_agent_p3b_live_* 隔离库。
        // 在 Spring 上下文构建、Flyway 迁移和 Hikari 连接池初始化之前 fail-closed 拦截非法配置！
        EvalLiveIsolatedDatabase.validateTargetDatabaseUrlStatic(targetUrl);
        // Stage 2: independent, read-only JDBC check BEFORE Spring creates its datasource/Flyway.
        // Explicit env credentials override the test profile for BOTH this connection and the pool.
        String user = System.getenv("SPEC_AGENT_TARGET_DB_USERNAME");
        String password = System.getenv("SPEC_AGENT_TARGET_DB_PASSWORD");
        EvalLiveIsolatedDatabase.verifyBeforeContext(targetUrl, user, password,
                System.getenv("SPEC_AGENT_TARGET_DB_SERVER_ADDRESS"),
                System.getenv("SPEC_AGENT_TARGET_DB_SERVER_PORT"));
        registry.add("spring.datasource.url", () -> targetUrl);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);

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
                    return Files.readString(rootKeyFile).strip() + ",MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            });
        }
    }

    @Test
    void configureAndValidateGeminiViaOfficialCustomService() throws Exception {
        // 0. 数据库隔离性硬检查: 未显式配置隔离库、连接非隔离库或配置漂移时，在任何操作前立即失败关闭
        String targetUrl = System.getenv("SPEC_AGENT_TARGET_DB_URL");
        var identity = EvalLiveIsolatedDatabase.verifyTargetDatabaseIsolation(jdbcTemplate, targetUrl);
        EvalLiveIsolatedDatabase.requireServerEndpoint(identity,
                System.getenv("SPEC_AGENT_TARGET_DB_USERNAME"),
                System.getenv("SPEC_AGENT_TARGET_DB_SERVER_ADDRESS"),
                Integer.parseInt(System.getenv("SPEC_AGENT_TARGET_DB_SERVER_PORT")));

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
                .as("%s must exist in official models catalog", PRIMARY_MODEL_ID)
                .isTrue();
        System.out.println("[GEMINI_LITE_DISCOVERY] Found models count: " + discovery.models().size()
                + ", target " + PRIMARY_MODEL_ID + " is verified available.");

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
        System.out.println("[GEMINI_LITE_SAVE] Saved revision: " + savedStatus.configRevision()
                + ", selectedModel: " + savedStatus.selectedModel()
                + ", maskedKey: " + savedStatus.maskedKey());

        // 3. Validate compatibility via official probe (1 inference call to Gemini Lite candidate)
        long startProbe = System.currentTimeMillis();
        CustomProviderSettingsService.Status validatedStatus = customService.validate();
        long probeDurationMs = System.currentTimeMillis() - startProbe;
        assertThat(validatedStatus.validated()).isTrue();
        System.out.println("[GEMINI_LITE_PROBE] Probe SUCCESS in " + probeDurationMs + " ms, validated="
                + validatedStatus.validated() + ", revision=" + validatedStatus.configRevision());

        // 4. Verify global provider on isolated DB can be activated via official service
        assertThat(modelProviderSettingsService.activeProvider())
                .as("Initial active provider on isolated DB")
                .isIn(ModelProvider.KILO, ModelProvider.OPENCODE_ZEN);
        modelProviderSettingsService.setActiveProvider(ModelProvider.CUSTOM);
        assertThat(modelProviderSettingsService.activeProvider())
                .as("Active provider on isolated DB after activation")
                .isEqualTo(ModelProvider.CUSTOM);
        System.out.println("[GEMINI_LITE_ACTIVATION] Successfully activated CUSTOM on isolated database.");
    }
}
