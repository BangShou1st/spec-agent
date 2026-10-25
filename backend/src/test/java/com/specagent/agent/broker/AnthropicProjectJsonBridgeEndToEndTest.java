package com.specagent.agent.broker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.protocol.AgentProtocol;
import com.specagent.agent.runevent.AgentRunEventRepository;
import com.specagent.agent.runtime.RunService;
import com.specagent.common.InMemoryRunModelBindingPort;
import com.specagent.model.contract.ActiveProviderPort;
import com.specagent.model.contract.CustomRuntimeSettingsPort;
import com.specagent.model.contract.ModelProvider;
import com.specagent.model.contract.OpenCodeRuntimeSettingsPort;
import com.specagent.model.contract.KiloRuntimeSettingsPort;
import com.specagent.model.contract.RuntimeCustomSettings;
import com.specagent.model.provider.AnthropicMessagesProtocolAdapter;
import com.specagent.model.provider.ChatCompletionsProtocolAdapter;
import com.specagent.model.provider.CustomInferenceGateway;
import com.specagent.model.provider.KiloInferenceGateway;
import com.specagent.model.provider.OpenCodeModelInferenceGateway;
import com.specagent.model.provider.ProtocolAdapterRegistry;
import com.specagent.model.provider.ResponsesProtocolAdapter;
import com.specagent.model.provider.RoutingModelInferenceGateway;
import com.specagent.modelsettings.ActiveModelTargetService;
import com.specagent.modelsettings.ModelProviderSettingsService;
import com.specagent.support.FakeAnthropicMessagesProvider;
import com.specagent.workspace.project.ProjectService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * MS-C3 Project Agent 闭环:InternalModelInferenceController(JSON_OBJECT 契约)
 * → RoutingModelInferenceGateway(冻结绑定)→ CustomInferenceGateway
 * → AnthropicMessagesProtocolAdapter(output_config.format 宿主 Envelope)
 * → Fake Anthropic /messages → Envelope 解包 → Python 侧消费的决策 JSON。
 * 任何非法 Envelope、refusal 或运行中配置漂移都失败关闭,绝不回退。
 */
@SpringBootTest
@ActiveProfiles("test")
@org.springframework.transaction.annotation.Transactional
class AnthropicProjectJsonBridgeEndToEndTest {

    private static final String STATE_UPDATE_OUTPUT = """
            {"claims":[{"kind":"goal","text":"The user clarified the main outcome.",\
            "status":"confirmed","confidence":0.9,"sourceRefs":[]}]}
            """;

    private static final String DECISION_OUTPUT = """
            {"observation":{"known":["The user clarified the main outcome."],\
            "unknowns":["The user must confirm scope boundaries."],"conflicts":[],"risks":[]},\
            "action":{"actionFamily":"REQUEST_USER_INPUT","payload":{"questionText":\
            "What is the most important outcome?","purpose":"This clarifies the primary \
            requirement goal.","options":[{"label":"Clarify the primary goal"}],\
            "allowFreeAnswer":true},"sourceRefs":[]}}
            """;

    @Autowired private ProjectService projectService;
    @Autowired private RunService runService;
    @Autowired private AgentRunEventRepository eventRepository;
    @Autowired private AgentBrainProperties properties;
    @Autowired private WorkspaceRpcGrants grants;
    @Autowired private RunExistenceCheck runExistenceCheck;
    @Autowired private RunProjectLookup runProjectLookup;
    @Autowired private com.specagent.agent.runevent.AgentRunEventService eventService;
    @Autowired private JdbcTemplate jdbc;

    private final ObjectMapper mapper = new ObjectMapper();
    private final InMemoryRunModelBindingPort runBindings = new InMemoryRunModelBindingPort();
    private FakeAnthropicMessagesProvider provider;
    private InternalModelInferenceController controller;
    private volatile RuntimeCustomSettings settings;

    @BeforeEach
    void fixture() throws Exception {
        provider = new FakeAnthropicMessagesProvider();
        provider.envelopeInnerJson = DECISION_OUTPUT;
        settings = new RuntimeCustomSettings("ANTHROPIC_MESSAGES", provider.baseUrl(),
                "fixture-secret", "anthropic-model", 1, UUID.randomUUID(), true);
        controller = controllerFor(settings);
    }

    @AfterEach
    void cleanup() {
        try {
            provider.close();
        } catch (Exception ignored) {
        }
    }

    private InternalModelInferenceController controllerFor(RuntimeCustomSettings current) {
        CustomRuntimeSettingsPort port = new CustomRuntimeSettingsPort() {
            @Override
            public RuntimeCustomSettings requireRuntimeSettings() {
                return current;
            }

            @Override
            public RuntimeCustomSettings requireRuntimeSettings(UUID selectedId) {
                if (!java.util.Objects.equals(current.providerId(), selectedId)) {
                    throw new IllegalStateException("GA_MODEL_BINDING_MISMATCH");
                }
                return current;
            }

            @Override
            public RuntimeCustomSettings requireRuntimeSettings(String frozenRevision) {
                if (!current.bindingRevision().equals(frozenRevision)) {
                    throw new IllegalStateException("GA_MODEL_BINDING_MISMATCH");
                }
                return current;
            }
        };
        var providers = mock(ModelProviderSettingsService.class);
        when(providers.activeProvider()).thenReturn(ModelProvider.CUSTOM);
        when(providers.selection()).thenAnswer(invocation -> new ModelProviderSettingsService.Selection(
                ModelProvider.CUSTOM, current.providerId()));
        var targets = new ActiveModelTargetService(providers,
                mock(OpenCodeRuntimeSettingsPort.class), mock(KiloRuntimeSettingsPort.class), port);
        var registry = new ProtocolAdapterRegistry(List.of(
                new ChatCompletionsProtocolAdapter(), new ResponsesProtocolAdapter(),
                new AnthropicMessagesProtocolAdapter()));
        var custom = new CustomInferenceGateway(port, registry, mapper);
        var routing = new RoutingModelInferenceGateway(mock(ActiveProviderPort.class), targets,
                mock(OpenCodeModelInferenceGateway.class), mock(KiloInferenceGateway.class),
                custom, runBindings);
        var built = new InternalModelInferenceController(routing, eventService, properties,
                runExistenceCheck, runProjectLookup);
        // grants 是历史遗留的 setter 注入;手动构造时必须显式注入同一生产实现。
        built.setGrants(grants);
        return built;
    }

    private UUID newRun() {
        var project = projectService.createProject("Anthropic bridge 测试项目");
        UUID run = runService.createQueuedDraftQuestion(project.id()).id();
        runService.claimDecisionCycleRun(run).orElseThrow();
        return run;
    }

    private String ticket(UUID run, String type) {
        var result = new java.util.concurrent.atomic.AtomicReference<String>();
        com.specagent.agent.runtime.WorkspaceExecutionContext.runWith(run, () -> result.set(
                grants.issue(run, type, 2, java.time.Duration.ofSeconds(120)).value()));
        return result.get();
    }

    private String body(UUID runId, String callType) throws Exception {
        var request = new ModelInferenceHttpRequest(AgentProtocol.INFERENCE_PROTOCOL_VERSION,
                runId, callType,
                List.of(new ModelInferenceHttpRequest.Message("system", "You are the requirement agent."),
                        new ModelInferenceHttpRequest.Message("user", "请澄清主要目标")),
                2048);
        return mapper.writeValueAsString(request);
    }

    private String eventText(UUID runId) {
        StringBuilder text = new StringBuilder();
        eventRepository.findByRunId(runId)
                .forEach(event -> text.append(event.eventType()).append(' ')
                        .append(event.payload().toString()).append('\n'));
        return text.toString();
    }

    @Test
    void decisionRoundTripServesPythonDecisionContractThroughTheEnvelope() throws Exception {
        UUID run = newRun();
        var response = controller.complete(properties.getInternalSecret(), ticket(run, "DECISION"),
                body(run, "DECISION"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().finishReason()).isEqualTo("end_turn");
        // 返回的是内层决策 JSON 对象本身(规范化),外层 Envelope 绝不出现。
        assertThat(response.getBody().content()).startsWith("{\"observation\":");
        assertThat(response.getBody().content()).contains("REQUEST_USER_INPUT");
        assertThat(response.getBody().content()).doesNotContain("\"json\":\"");
        assertThat(response.getBody().content()).doesNotContain("output_config");

        // Provider 收到的是合法 Anthropic 结构化输出请求。
        assertThat(provider.requests).hasSize(1);
        var sent = provider.requests.getFirst();
        assertThat(sent.path("model").asText()).isEqualTo("anthropic-model");
        assertThat(sent.path("output_config").path("format").path("type").asText())
                .isEqualTo("json_schema");
        assertThat(sent.path("output_config").path("format").path("schema")
                .path("additionalProperties").asBoolean()).isFalse();
        assertThat(sent.path("messages").get(0).path("role").asText()).isEqualTo("user");
        assertThat(sent.has("response_format")).isFalse();
        assertThat(provider.apiKeys).containsOnly("fixture-secret");
        assertThat(provider.versions).containsOnly(AnthropicMessagesProtocolAdapter.ANTHROPIC_VERSION);
        // Envelope 指令与原 system 合并,保留原语义。
        assertThat(sent.path("system").asText()).contains("You are the requirement agent.")
                .contains("\"json\"");

        assertThat(eventText(run)).contains("MODEL_INFERENCE").contains("outputSha256");
        assertThat(eventText(run)).doesNotContain("fixture-secret");
    }

    @Test
    void stateUpdateRoundTripServesTheSameEnvelopeBridge() throws Exception {
        provider.envelopeInnerJson = STATE_UPDATE_OUTPUT;
        UUID run = newRun();
        var response = controller.complete(properties.getInternalSecret(), ticket(run, "STATE_UPDATE"),
                body(run, "STATE_UPDATE"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().finishReason()).isEqualTo("end_turn");
        assertThat(response.getBody().content()).startsWith("{\"claims\":[");
        assertThat(response.getBody().content()).contains("\"kind\":\"goal\"");
        assertThat(provider.requests.getFirst().path("output_config").path("format")
                .path("type").asText()).isEqualTo("json_schema");
    }

    @Test
    void modelEnvelopeViolationsFailClosedWithTypedFailureEvent() throws Exception {
        UUID run = newRun();
        provider.envelopeFailure = FakeAnthropicMessagesProvider.EnvelopeFailure.RAW_NO_ENVELOPE;
        var raw = controller.complete(properties.getInternalSecret(), ticket(run, "DECISION"),
                body(run, "DECISION"));
        assertThat(raw.getStatusCode().value()).isEqualTo(502);
        assertThat(raw.getBody()).isNotNull();
        assertThat(raw.getBody().finishReason()).isEqualTo("error");
        assertThat(raw.getBody().content()).isEmpty();
        assertThat(eventText(run)).contains("MODEL_INFERENCE_FAILED");

        // 内层不是对象:同样失败关闭,绝不把数组当决策输出。
        UUID second = newRun();
        provider.envelopeFailure = FakeAnthropicMessagesProvider.EnvelopeFailure.INNER_NOT_OBJECT;
        var notObject = controller.complete(properties.getInternalSecret(), ticket(second, "DECISION"),
                body(second, "DECISION"));
        assertThat(notObject.getStatusCode().value()).isEqualTo(502);

        // Provider refusal:合法 envelope 形状也不是成功。
        UUID third = newRun();
        provider.envelopeFailure = FakeAnthropicMessagesProvider.EnvelopeFailure.NONE;
        provider.refusalStop = true;
        var refused = controller.complete(properties.getInternalSecret(), ticket(third, "DECISION"),
                body(third, "DECISION"));
        assertThat(refused.getStatusCode().value()).isEqualTo(502);
        provider.refusalStop = false;
    }

    @Test
    void configurationDriftMidRunFailsClosedWithoutRetargeting() throws Exception {
        UUID run = newRun();
        var first = controller.complete(properties.getInternalSecret(), ticket(run, "DECISION"),
                body(run, "DECISION"));
        assertThat(first.getStatusCode().value()).isEqualTo(200);
        int providerCalls = provider.requests.size();

        // 运行中修改 URL/Key/Revision:同一 Run 的后续推理必须失败关闭,绝不重定向。
        settings = new RuntimeCustomSettings("ANTHROPIC_MESSAGES", provider.baseUrl(),
                "rotated-secret", "anthropic-model", 2, settings.providerId(), true);
        controller = controllerFor(settings);
        var drifted = controller.complete(properties.getInternalSecret(), ticket(run, "DECISION"),
                body(run, "DECISION"));

        assertThat(drifted.getStatusCode().value()).isEqualTo(502);
        assertThat(drifted.getBody().finishReason()).isEqualTo("error");
        assertThat(provider.requests.size()).isEqualTo(providerCalls);
    }
}
