package com.specagent.assistant;

import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.conversation.GlobalAssistantRun;
import com.specagent.assistant.runtime.GaExecutionCoordinator;
import com.specagent.assistant.runtime.GaExecutionPreparation;
import com.specagent.model.provider.OpenCodeModelCatalog;
import com.specagent.model.provider.OpenCodeZenTransport;
import com.specagent.modelsettings.OpenCodeSettings;
import com.specagent.modelsettings.OpenCodeSettingsRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 文件名:OpenCodeModelProtocolRuntimeGateIntegrationTest.java
 *
 * 测试目标:GA 运行期协议门禁(失败关闭)。宿主只实现 {@code /chat/completions}
 * 调用链,因此只有经可信元数据确认为 Chat Completions 的模型才允许进入执行信封;
 * 升级前保存的 Responses / Anthropic / UNKNOWN 模型必须被拒绝——拒绝发生在信封
 * 创建、预算认领、Python 派发之前,且 run 按控制面语义终态化。
 *
 * <p>元数据来自本机 stub(用真实端点已核对的形态构造),Zen 上游被桩化,<b>绝不触网</b>。
 * 未登记的模型不进目录,严格复刻"未登记即未验证"。
 */
@SpringBootTest
@ActiveProfiles("test")
class OpenCodeModelProtocolRuntimeGateIntegrationTest {

    private static final String METADATA = """
            {"opencode":{"id":"opencode","npm":"@ai-sdk/openai-compatible",
              "models":{
                "verified-chat":{"cost":{"input":0,"output":0}},
                "paid-chat":{"cost":{"input":1,"output":2}},
                "paid-named-free":{"cost":{"input":1,"output":2}},
                "responses-model":{"provider":{"npm":"@ai-sdk/openai"},"cost":{"input":0,"output":0}},
                "anthropic-model":{"provider":{"npm":"@ai-sdk/anthropic"},"cost":{"input":5,"output":25}}
              }}}
            """;

    private static final HttpServer METADATA_STUB = startMetadataStub();

    @DynamicPropertySource
    static void metadataUrl(DynamicPropertyRegistry registry) {
        registry.add("spec.agent.model.opencode.metadata-url",
                () -> "http://127.0.0.1:" + METADATA_STUB.getAddress().getPort() + "/api.json");
    }

    @Autowired GlobalAssistantConversationService conversations;
    @Autowired GaExecutionPreparation preparation;
    @Autowired GaExecutionCoordinator coordinator;
    @Autowired OpenCodeModelCatalog catalog;
    @Autowired JdbcTemplate jdbc;
    @MockBean OpenCodeSettingsRepository settings;
    @MockBean OpenCodeZenTransport transport;

    @BeforeEach
    void fixture() {
        // 预热可信元数据缓存(生产由 GA 分发入口在进入准备事务之前完成)。
        catalog.warmMetadata();
    }

    // 1. 已保存 CHAT 模型,GA 正常准备执行。
    @Test
    void savedVerifiedChatModelStillPreparesTheExecutionEnvelope() {
        savedModel("verified-chat");
        UUID thread = conversations.createThread().id();
        GlobalAssistantRun run = run(thread, "chat 正常准备");

        var prepared = preparation.prepare(thread, run.id(), "chat 正常准备", null);

        assertThat(prepared).isNotNull();
        assertThat(prepared.model()).isEqualTo("verified-chat");
        assertThat(runStatus(run.id())).isEqualTo("RUNNING");
        assertThat(envelopeCount(run.id())).isEqualTo(1);
    }

    // 2/3/4. 已保存 RESPONSES / ANTHROPIC / UNKNOWN 模型,均在信封创建前明确拒绝。
    @Test
    void savedNonChatOrUnverifiableModelsAreRejectedBeforeEnvelopeCreation() {
        for (String model : List.of("responses-model", "anthropic-model", "unregistered-model")) {
            savedModel(model);
            UUID thread = conversations.createThread().id();
            GlobalAssistantRun run = run(thread, "拒绝 " + model);

            assertThat(preparation.prepare(thread, run.id(), "拒绝 " + model, null))
                    .as("非 Chat 或无法确认的模型必须被拒绝: %s", model).isNull();
            assertThat(runStatus(run.id())).as(model).isEqualTo("FAILED");
            assertThat(runErrorCode(run.id())).as(model).isEqualTo("GA_MODEL_PROTOCOL_UNSUPPORTED");
            assertThat(envelopeCount(run.id())).as(model).isZero();
            assertThat(callCount(run.id())).as(model).isZero();
            assertThat(assistantMessageCount(thread, run.id())).as(model).isZero();
        }
        verifyNoInteractions(transport);
    }

    // 6. 非 Chat 模型不会产生错误的 /chat/completions 请求(经真实分发入口)。
    @Test
    void nonChatModelNeverProducesAChatCompletionsRequestThroughTheDispatchEntry() {
        savedModel("responses-model");
        UUID thread = conversations.createThread().id();
        GlobalAssistantRun run = run(thread, "分发拒绝");

        coordinator.executeRun(thread, run.id(), "分发拒绝", null);

        assertThat(runStatus(run.id())).isEqualTo("FAILED");
        assertThat(runErrorCode(run.id())).isEqualTo("GA_MODEL_PROTOCOL_UNSUPPORTED");
        assertThat(envelopeCount(run.id())).isZero();
        verifyNoInteractions(transport);
    }

    // 7. 协议拒绝后 run 正确终态化,不残留活动 run 锁。
    @Test
    void protocolRejectionTerminalizesTheRunAndLeavesNoActiveRunLock() {
        savedModel("anthropic-model");
        UUID thread = conversations.createThread().id();
        GlobalAssistantRun run = run(thread, "终态化");

        assertThat(preparation.prepare(thread, run.id(), "终态化", null)).isNull();

        assertThat(runStatus(run.id())).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM global_assistant_runs WHERE thread_id=? AND status IN ('CREATED','RUNNING')",
                Integer.class, thread)).isZero();
        assertThat(envelopeCount(run.id())).isZero();
        // 同一会话可以立即开始下一轮:失败关闭没有残留活动 run(uq_ga_runs_single_active)。
        assertThat(run(thread, "下一轮").id()).isNotNull();
    }

    // 8. 合法 Chat 模型(含免费)运行期仍被接受,与目录分类一致。
    @Test
    void verifiedChatModelsStayExecutableAtRuntime() {
        assertThat(catalog.verifiedChatExecutable("verified-chat")).isTrue();
        assertThat(catalog.verifiedChatExecutable("paid-chat")).isTrue();
        assertThat(catalog.verifiedChatExecutable("responses-model")).isFalse();
        assertThat(catalog.verifiedChatExecutable("anthropic-model")).isFalse();
        assertThat(catalog.verifiedChatExecutable("unregistered-model")).isFalse();
        assertThat(catalog.verifiedChatExecutable(null)).isFalse();
        assertThat(catalog.verifiedChatExecutable(" ")).isFalse();
    }

    // 10. 已知付费模型不会仅凭 -free 后缀被错误标记为免费。
    @Test
    void paidModelWithFreeSuffixIsNotTreatedAsFree() {
        assertThat(catalog.isFreeModel("paid-named-free")).isFalse();
        assertThat(catalog.isFreeModel("verified-chat")).isTrue();
        // 付费的 Chat 模型仍可执行(免费与协议是两个独立维度)。
        assertThat(catalog.verifiedChatExecutable("paid-named-free")).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private void savedModel(String model) {
        Instant now = Instant.now();
        when(settings.find()).thenReturn(Optional.of(
                new OpenCodeSettings("test-key-no-network", "work", model, now, now)));
    }

    private GlobalAssistantRun run(UUID thread, String text) {
        return conversations.createRunWithUserMessage(thread, text, "ga-test", "ga-test", "ga-test");
    }

    private String runStatus(UUID runId) {
        return jdbc.queryForObject("SELECT status FROM global_assistant_runs WHERE id=?", String.class, runId);
    }

    private String runErrorCode(UUID runId) {
        return jdbc.queryForObject("SELECT error_code FROM global_assistant_runs WHERE id=?", String.class, runId);
    }

    private int envelopeCount(UUID runId) {
        return jdbc.queryForObject("SELECT count(*) FROM ga_executions WHERE run_id=?", Integer.class, runId);
    }

    private int callCount(UUID runId) {
        return jdbc.queryForObject("SELECT count(*) FROM ga_execution_calls WHERE run_id=?", Integer.class, runId);
    }

    private int assistantMessageCount(UUID thread, UUID runId) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM global_assistant_messages WHERE thread_id=? AND run_id=? AND role='ASSISTANT'
                """, Integer.class, thread, runId);
    }

    private static HttpServer startMetadataStub() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api.json", OpenCodeModelProtocolRuntimeGateIntegrationTest::respond);
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
