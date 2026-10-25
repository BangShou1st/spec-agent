package com.specagent.modelsettings;

import com.specagent.eval.EvalLiveIsolatedDatabase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 文件名: MsCrossChatCompletionsProbeLiveTest.java
 *
 * 用途: MS-CROSS 轮真实验证(opt-in,绝不进 CI):一个普通 UUID Custom 连接
 * (Chat Completions 协议、Google Gemini OpenAI 兼容端点、免费层模型)经过
 * ModelProvidersService.validate 的三能力资格探测。该探测在 MS-CROSS 修复
 * (probe 统一走生产原生传输)后覆盖:probeChat、probeCustom(FINAL 决策)与
 * probeNativeTools(经 ChatCompletionsGaTextTransport 的 probe_echo 双轮)。
 *
 * 凭据规则与既有 Gemini live 套件一致:运行时从桌面 key2.txt 读取,绝不写入
 * 代码、配置或日志;仅在隔离库操作;不切换全局激活状态。真实请求预算:本套件
 * 恰好 4 次推理调用(probeChat 1 + probeCustom 1 + probeNativeTools 2),无重试。
 */
@SpringBootTest
@org.junit.jupiter.api.Tag("external-service")
@org.junit.jupiter.api.Tag("database-integration")
@ActiveProfiles("test")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(
        named = "SPEC_AGENT_MS_CROSS_LIVE", matches = "true")
class MsCrossChatCompletionsProbeLiveTest {

    static {
        // 与既有 Gemini live 套件相同的本机出站代理(仅测试进程内生效)。
        System.setProperty("https.proxyHost", "127.0.0.1");
        System.setProperty("https.proxyPort", "7897");
        System.setProperty("http.nonProxyHosts", "localhost|127.0.0.1");
    }

    private static final String GEMINI_OPENAI_BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/openai";
    private static final String MODEL_ID = System.getenv().getOrDefault(
            "SPEC_AGENT_GEMINI_MODEL_ID", "gemini-3.5-flash-lite");

    @Autowired
    private ModelProvidersService providers;

    @Autowired
    private ModelConnectionCapabilities capabilities;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @DynamicPropertySource
    static void configureTargetDatabase(DynamicPropertyRegistry registry) {
        String targetUrl = System.getenv("SPEC_AGENT_TARGET_DB_URL");
        EvalLiveIsolatedDatabase.validateTargetDatabaseUrlStatic(targetUrl);
        String user = System.getenv("SPEC_AGENT_TARGET_DB_USERNAME");
        String password = System.getenv("SPEC_AGENT_TARGET_DB_PASSWORD");
        EvalLiveIsolatedDatabase.verifyBeforeContext(targetUrl, user, password,
                System.getenv("SPEC_AGENT_TARGET_DB_SERVER_ADDRESS"),
                System.getenv("SPEC_AGENT_TARGET_DB_SERVER_PORT"));
        registry.add("spring.datasource.url", () -> targetUrl);
        registry.add("spring.datasource.username", () -> user);
        registry.add("spring.datasource.password", () -> password);
        String testMasterKey = System.getenv("SPEC_AGENT_TEST_MASTER_KEY");
        if (testMasterKey != null && !testMasterKey.isBlank()) {
            registry.add("spec.agent.secret.master-key", testMasterKey::strip);
        }
    }

    @Test
    void uuidConnectionQualifiesThreeCapabilitiesThroughProductionTransports() throws Exception {
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

        // 1. 普通用户路径:创建一个 UUID Custom 连接(不依赖任何目录白名单)。
        ModelProviderRecord created = providers.create(new ModelProvidersService.Patch(
                "CUSTOM", "MS-CROSS Live Gemini", "CHAT_COMPLETIONS",
                GEMINI_OPENAI_BASE_URL, apiKey, MODEL_ID, "MANUAL"));
        assertThat(created.preset().name()).isEqualTo("CUSTOM");
        assertThat(created.configRevision()).isEqualTo(1L);

        // 2. validate 恰好执行三次资格探测:CHAT、STRUCTURED(FINAL 决策)、
        //    NATIVE_TOOLS(probe_echo 双轮,经修复后的生产传输)。真实请求共 4 次。
        ModelProviderRecord validated = providers.validate(created.id());

        // 3. 三能力都必须在 (org, UUID, revision=1) 上记录为 VERIFIED。
        Map<String, String> status = capabilities.status(validated);
        System.out.println("[MS_CROSS_LIVE] capabilities=" + status.keySet().stream()
                .map(k -> k + "=" + status.get(k)).sorted().reduce((a, b) -> a + "," + b).orElse(""));
        assertThat(status.get("CHAT")).isEqualTo("VERIFIED");
        assertThat(status.get("STRUCTURED")).isEqualTo("VERIFIED");
        assertThat(status.get("NATIVE_TOOLS")).isEqualTo("VERIFIED");

        // 4. STRUCTURED=VERIFIED 使 validated 标记落在当前修订上(激活门禁输入)。
        assertThat(validated.validated()).isTrue();
        assertThat(validated.validatedRevision()).isEqualTo(validated.configRevision());

        // 5. 清理:删除本测试创建的连接(能力记录级联删除),保持隔离库干净。
        providers.delete(created.id());
        assertThatThrownBy(() -> providers.require(created.id()))
                .isInstanceOf(com.specagent.model.provider.ModelProviderException.class);
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM model_connection_capabilities WHERE provider_id=?",
                Integer.class, created.id());
        assertThat(remaining).isZero();
    }
}
