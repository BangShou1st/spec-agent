package com.specagent.agent.broker;

import com.specagent.agent.protocol.AgentContracts;
import com.specagent.agent.protocol.AgentProtocol;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.broker.AgentBrainProperties;
import com.specagent.model.contract.ModelInferenceGateway;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelInferenceResponse;
import com.specagent.model.contract.ModelOutputContract;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 文件名:InternalModelInferenceContractTest.java
 *
 * 测试目标:BUG-03 回归——内部推理 broker 服务于 Python agent 大脑,后者把模型输出
 * 按严格 JSON 解析({@code engine._parse_model_output -> json.loads}),遇到其他形式
 * (markdown 围栏或散文包裹的 JSON)会以 502 {@code brain_failure} fail-closed。
 * 因此 broker 必须为大脑调用请求结构化 JSON 输出契约,阻止模型返回非 JSON 文本。
 * 通过单一表驱动用例断言每个封闭的大脑调用类型(STATE_UPDATE、DECISION、
 * ARTIFACT_GENERATION)都请求 {@link ModelOutputContract.JsonObject};
 * 旧行为下该测试失败,修复后通过。另验证会话身份绑定到所属 project,
 * 无法解析 project 时回退为 run 而不是让调用失败。
 */
@ExtendWith(MockitoExtension.class)
class InternalModelInferenceContractTest {

    @Mock
    private ModelInferenceGateway gateway;
    @Mock
    private AgentRunEventService eventService;
    @Mock
    private AgentBrainProperties properties;
    @Mock
    private RunExistenceCheck runExistenceCheck;
    @Mock
    private RunProjectLookup runProjectLookup;

    @BeforeEach
    void setUp() {
        when(properties.getInternalSecret()).thenReturn("dev-internal-secret");
        when(properties.getBroker()).thenReturn(new AgentBrainProperties.Broker());
        when(runExistenceCheck.exists(any())).thenReturn(true);
        when(gateway.complete(any(ModelInferenceRequest.class)))
                .thenReturn(new ModelInferenceResponse("{}", "stop", 0, 0));
    }

    private InternalModelInferenceController controller() {
        return new InternalModelInferenceController(gateway, eventService, properties,
                runExistenceCheck, runProjectLookup);
    }

    private String bodyFor(String callType) {
        return bodyFor(callType, UUID.randomUUID());
    }

    private String bodyFor(String callType, UUID runId) {
        ModelInferenceHttpRequest request = new ModelInferenceHttpRequest(
                AgentProtocol.INFERENCE_PROTOCOL_VERSION,
                runId,
                callType,
                List.of(new ModelInferenceHttpRequest.Message("user", "hi")),
                1000);
        return AgentContracts.write(request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STATE_UPDATE", "DECISION", "ARTIFACT_GENERATION"})
    void brainCallRequestsJsonObjectContract(String callType) {
        controller().complete("dev-internal-secret", bodyFor(callType));

        ArgumentCaptor<ModelInferenceRequest> captor = ArgumentCaptor.forClass(ModelInferenceRequest.class);
        verify(gateway).complete(captor.capture());
        assertThat(captor.getValue().outputContract())
                .describedAs("Brain call type %s must request structured JSON so the model "
                        + "cannot return fenced/prose JSON that breaks json.loads", callType)
                .isInstanceOf(ModelOutputContract.JsonObject.class);
    }

    @Test
    void brokerPassesTheOwningProjectAsTheConversationIdentity() {
        UUID runId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        when(runProjectLookup.projectIdOf(runId)).thenReturn(projectId);

        controller().complete("dev-internal-secret", bodyFor("DECISION", runId));

        ArgumentCaptor<ModelInferenceRequest> captor = ArgumentCaptor.forClass(ModelInferenceRequest.class);
        verify(gateway).complete(captor.capture());
        // 一个 project 就是一个 provider 侧会话。
        assertThat(captor.getValue().conversationId()).isEqualTo(projectId);
        assertThat(captor.getValue().conversationOrRun()).isEqualTo(projectId);
    }

    @Test
    void unresolvableProjectFallsBackToTheRunInsteadOfFailingTheCall() {
        UUID runId = UUID.randomUUID();
        when(runProjectLookup.projectIdOf(runId)).thenReturn(null);

        controller().complete("dev-internal-secret", bodyFor("DECISION", runId));

        ArgumentCaptor<ModelInferenceRequest> captor = ArgumentCaptor.forClass(ModelInferenceRequest.class);
        verify(gateway).complete(captor.capture());
        assertThat(captor.getValue().conversationId()).isNull();
        assertThat(captor.getValue().conversationOrRun()).isEqualTo(runId);
    }
}
