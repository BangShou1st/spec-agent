package com.specagent.modelsettings;

import com.specagent.model.provider.OpenCodeModel;
import com.specagent.model.provider.OpenCodeModelCatalog;
import com.specagent.model.provider.OpenCodeModelException;
import com.specagent.model.provider.OpenCodeModelList;
import com.specagent.model.provider.OpenCodeModelProtocol;
import com.specagent.model.provider.OpenCodeZenTransport;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * 文件名:OpenCodeModelProtocolGateIntegrationTest.java
 *
 * 测试目标:真实装配下的协议/免费门禁(模型目录元数据来自本机 stub 服务器,Zen 上游被桩化,
 * <b>绝不触网</b>)。
 *
 * <p>用真实端点已核对的形态构造目录:{@code big-pickle} 无 {@code -free} 后缀但价格为 0;
 * {@code muse-spark-1.3-contributor-free} 免费但走 Responses 协议;{@code claude-opus-5}
 * 是 Anthropic 协议;{@code deepseek-v4-pro} 是付费的 Chat 模型;还有一个元数据未登记的模型。
 *
 * <p>断言的不变量(对应本轮明确的红线):
 * <ul>
 *   <li>{@code listAllModels} 照旧暴露提供商全部模型(记录事实,不隐藏);</li>
 *   <li>{@code listAvailableModels} / {@code listFreeModels} 只含协议已验证为
 *       Chat Completions 的模型——前端因此不可能显示一个"必然失败"的可选模型;</li>
 *   <li>免费判定来自价格元数据双零,因此没有 {@code -free} 后缀的免费模型会出现,
 *       未登记的模型不被假设为免费;</li>
 *   <li>保存/切换模型的入口拒绝异协议与未登记模型,已保存的目录流程保持原样。</li>
 * </ul>
 */
@SpringBootTest
@ActiveProfiles("test")
class OpenCodeModelProtocolGateIntegrationTest {

    private static final String METADATA = """
            {"opencode":{"id":"opencode","npm":"@ai-sdk/openai-compatible",
              "models":{
                "big-pickle":{"cost":{"input":0,"output":0}},
                "cat-jam-free":{"cost":{"input":0,"output":0}},
                "deepseek-v4-pro":{"cost":{"input":1.2,"output":3.4}},
                "paid-named-free":{"cost":{"input":1,"output":2}},
                "muse-spark-1.3-contributor-free":{"provider":{"npm":"@ai-sdk/openai"},
                  "cost":{"input":0,"output":0}},
                "claude-opus-5":{"provider":{"npm":"@ai-sdk/anthropic"},"cost":{"input":5,"output":25}},
                "muse-spark-1.2-contributor-free":{"status":"deprecated","cost":{"input":0,"output":0}}
              }}}
            """;

    /** 本机 stub:官方模型目录元数据的替代品;随类加载启动,不访问公网。 */
    private static final HttpServer METADATA_STUB = startMetadataStub();

    @DynamicPropertySource
    static void metadataUrl(DynamicPropertyRegistry registry) {
        registry.add("spec.agent.model.opencode.metadata-url",
                () -> "http://127.0.0.1:" + METADATA_STUB.getAddress().getPort() + "/api.json");
    }

    @Autowired OpenCodeModelCatalog catalog;
    @Autowired OpenCodeSettingsService settings;
    @Autowired ProviderModelCatalogService providerCatalog;
    @Autowired OpenCodeSettingsRepository repository;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @MockBean OpenCodeZenTransport transport;

    @BeforeEach
    void fixture() {
        jdbc.getJdbcTemplate().update("DELETE FROM opencode_settings");
        // 运行期门禁只读已缓存的可信元数据(绝不触发网络刷新);这里预热缓存,
        // 等价于生产路径中 GA 分发入口在进入准备事务之前完成的预热。
        catalog.warmMetadata();
        when(transport.listModels(any())).thenReturn(new OpenCodeModelList(List.of(
                new OpenCodeModel("big-pickle", "opencode"),
                new OpenCodeModel("cat-jam-free", "opencode"),
                new OpenCodeModel("deepseek-v4-pro", "opencode"),
                new OpenCodeModel("muse-spark-1.3-contributor-free", "opencode"),
                new OpenCodeModel("claude-opus-5", "opencode"),
                new OpenCodeModel("unregistered-model", "opencode"))));
        doNothing().when(transport).validateCredential(any(), any());
    }

    @Test
    void catalogRecordsProtocolAndOnlyOffersVerifiedChatModels() {
        assertThat(catalog.protocolOf("big-pickle")).isEqualTo(OpenCodeModelProtocol.CHAT);
        assertThat(catalog.protocolOf("muse-spark-1.3-contributor-free"))
                .isEqualTo(OpenCodeModelProtocol.RESPONSES);
        assertThat(catalog.protocolOf("claude-opus-5")).isEqualTo(OpenCodeModelProtocol.ANTHROPIC);
        assertThat(catalog.protocolOf("unregistered-model")).isEqualTo(OpenCodeModelProtocol.UNKNOWN);

        // 暴露事实不隐藏:全部提供商模型仍可被列出。
        assertThat(catalog.listAllModels("candidate-key")).contains(
                "muse-spark-1.3-contributor-free", "claude-opus-5", "unregistered-model");
        // 选择列表只含协议已验证的 Chat 模型(付费 Chat 模型也在其中)。
        assertThat(catalog.listAvailableModels("candidate-key"))
                .containsExactly("big-pickle", "cat-jam-free", "deepseek-v4-pro");
    }

    @Test
    void freeDecisionUsesPriceMetadataInsteadOfTheNameSuffix() {
        assertThat(catalog.listFreeModels("candidate-key"))
                .as("big-pickle 没有 -free 后缀但价格元数据为 0;其他带 -free 的模型不是 Chat 协议")
                .containsExactly("big-pickle", "cat-jam-free");
        assertThat(catalog.isFreeModel("big-pickle")).isTrue();
        assertThat(catalog.isFreeModel("deepseek-v4-pro")).isFalse();
        assertThat(catalog.isFreeModel("muse-spark-1.3-contributor-free"))
                .as("价格元数据证明它免费——但协议不是 Chat,因此不进免费选择列表")
                .isTrue();
        assertThat(catalog.chatExecutable("muse-spark-1.3-contributor-free")).isFalse();
        assertThat(catalog.isFreeModel("unregistered-model"))
                .as("元数据未登记 → 未被证明免费").isFalse();
        assertThat(catalog.isFreeModel("paid-named-free"))
                .as("名字以 -free 结尾但价格元数据显示收费 → 不算免费").isFalse();
        assertThat(catalog.isFreeModel("muse-spark-1.2-contributor-free"))
                .as("已下线模型即使免费也不可选").isFalse();
    }

    @Test
    void runtimeGateAgreesWithEverySelectionEntrypointWhenMetadataIsAvailable() {
        // 保存/切换/列表入口只接受协议已验证的 Chat 模型(见 savingRefuses...),
        // 运行期门禁对同一事实给出同一个结论——前端可选 == 运行期可执行。
        assertThat(catalog.verifiedChatExecutable("big-pickle")).isTrue();
        assertThat(catalog.verifiedChatExecutable("deepseek-v4-pro")).isTrue();
        assertThat(catalog.verifiedChatExecutable("muse-spark-1.3-contributor-free")).isFalse();
        assertThat(catalog.verifiedChatExecutable("claude-opus-5")).isFalse();
        assertThat(catalog.verifiedChatExecutable("unregistered-model")).isFalse();
        assertThat(catalog.verifiedChatExecutable("muse-spark-1.2-contributor-free")).isFalse();

        settings.save("candidate-key", "big-pickle");
        assertThat(settings.requireRuntimeSettings().selectedModel()).isEqualTo("big-pickle");
        assertThat(catalog.verifiedChatExecutable(settings.requireRuntimeSettings().selectedModel()))
                .as("已保存的工作模型必然通过运行期协议门禁").isTrue();
    }

    @Test
    void providerDiscoveryNeverExposesAModelTheChatCallChainWouldFailOn() {
        var discovery = providerCatalog.probe("OPENCODE_ZEN", null, null, "candidate-key", null);

        assertThat(discovery.allModels()).containsExactly("big-pickle", "cat-jam-free", "deepseek-v4-pro");
        assertThat(discovery.freeModels()).containsExactly("big-pickle", "cat-jam-free");
        assertThat(discovery.manualModel()).isFalse();
        assertThat(discovery.allModels()).doesNotContain(
                "muse-spark-1.3-contributor-free", "claude-opus-5", "unregistered-model");
    }

    @Test
    void savingRefusesNonChatOrUnverifiedProtocolAndKeepsTheCredentialFlowUnchanged() {
        for (String refused : List.of("muse-spark-1.3-contributor-free", "claude-opus-5",
                "unregistered-model", "muse-spark-1.2-contributor-free")) {
            assertThatThrownBy(() -> settings.save("candidate-key", refused))
                    .as("异协议/未验证模型不得被保存为工作模型: %s", refused)
                    .isInstanceOf(OpenCodeModelException.class);
        }
        assertThat(repository.find())
                .as("被拒绝的保存绝不落库(旧配置因此不受影响)")
                .isEmpty();

        // 价格元数据证明免费的 Chat 模型可以正常保存,凭据与模型选择流程与原来一致。
        OpenCodeSettingsStatus saved = settings.save("candidate-key", "big-pickle");
        assertThat(saved.configured()).isTrue();
        assertThat(saved.selectedModel()).isEqualTo("big-pickle");
        assertThat(settings.requireRuntimeSettings().selectedModel()).isEqualTo("big-pickle");
        var listed = settings.listModels();
        assertThat(listed.freeModels()).containsExactly("big-pickle", "cat-jam-free");
        assertThat(listed.allModels()).containsExactly("big-pickle", "cat-jam-free", "deepseek-v4-pro");
    }

    private static HttpServer startMetadataStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api.json", OpenCodeModelProtocolGateIntegrationTest::respond);
            server.start();
            return server;
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to start metadata stub", ex);
        }
    }

    private static void respond(HttpExchange exchange) throws IOException {
        byte[] payload = METADATA.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
