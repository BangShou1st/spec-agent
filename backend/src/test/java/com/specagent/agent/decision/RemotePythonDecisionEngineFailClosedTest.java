package com.specagent.agent.decision;

import com.specagent.agent.broker.AgentBrainProperties;
import com.specagent.agent.protocol.AgentContracts;
import com.specagent.agent.protocol.AgentContractException;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.decision.AgentBrainUnavailableException;
import com.specagent.agent.decision.RemotePythonDecisionEngine;
import com.specagent.agent.protocol.ActionIneligibleException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * 文件名:RemotePythonDecisionEngineFailClosedTest.java
 *
 * 测试目标:验证远程 Python 决策引擎的 fail-closed 行为——未知协议版本、错误 runId、
 * 捏造来源引用、大脑不可达等都必须产生带类型的失败,且不重试、不降级。失败码用于区分
 * 根因:模型输出畸形与引用越界不是"大脑宕机",慢响应也不是"不可达"。
 */
class RemotePythonDecisionEngineFailClosedTest {

    private static final Path FIXTURES = Path.of("../contracts/fixtures");

    private HttpServer server;
    private RemotePythonDecisionEngine engine;
    private final AtomicReference<String> responseBody = new AtomicReference<>("{}");
    private final AtomicReference<Integer> status = new AtomicReference<>(200);
    private final AtomicReference<Long> latencyMillis = new AtomicReference<>(0L);
    private final AtomicInteger requests = new AtomicInteger();

    @BeforeEach
    void startStubBrain() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/v1/decisions", exchange -> {
            requests.incrementAndGet();
            sleepIfSlow();
            byte[] body = responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status.get(), body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        AgentBrainProperties properties = new AgentBrainProperties();
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setInternalSecret("test-secret");
        engine = new RemotePythonDecisionEngine(properties);
    }

    private void sleepIfSlow() {
        long latency = latencyMillis.get();
        if (latency <= 0) {
            return;
        }
        try {
            Thread.sleep(latency);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    @AfterEach
    void stopStubBrain() {
        server.stop(0);
    }

    private AgentRequestEnvelope request() throws Exception {
        return AgentContracts.read(
                Files.readString(FIXTURES.resolve("agent-input-valid.json")),
                AgentRequestEnvelope.class);
    }

    private BrainFailureCode failureCodeOf(String body, int httpStatus) throws Exception {
        status.set(httpStatus);
        responseBody.set(body);
        Throwable failure = catchThrowable(() -> engine.runDecision(request()));
        assertThat(failure).isInstanceOf(AgentBrainUnavailableException.class);
        return ((AgentBrainUnavailableException) failure).failureCode();
    }

    @Test
    void unknownResponseProtocolVersionIsRejected() throws Exception {
        responseBody.set("""
                {"protocolVersion":"agent-decision.v9","runId":"22222222-2222-2222-2222-222222222222",
                 "stateUpdate":null,"observation":null,"actionProposal":null,
                 "usage":{"modelCalls":0,"promptHashes":[]},"diagnostics":{}}""");
        assertThatThrownBy(() -> engine.runDecision(request()))
                .isInstanceOf(AgentContractException.class)
                .hasMessageContaining("protocol");
    }

    @Test
    void responseWithUnknownFieldIsRejected() throws Exception {
        responseBody.set("""
                {"protocolVersion":"agent-decision.v2","runId":"22222222-2222-2222-2222-222222222222",
                 "mysteryField":true,"stateUpdate":null,"observation":null,"actionProposal":null,
                 "usage":{"modelCalls":0,"promptHashes":[]},"diagnostics":{}}""");
        assertThatThrownBy(() -> engine.runDecision(request()))
                .isInstanceOf(AgentContractException.class);
    }

    @Test
    void responseWithWrongRunIdIsRejectedBeforeAnyUse() throws Exception {
        String valid = Files.readString(FIXTURES.resolve("decision-response-valid.json"));
        responseBody.set(valid.replace("22222222-2222-2222-2222-222222222222",
                "33333333-3333-3333-3333-333333333333"));
        assertThatThrownBy(() -> engine.runDecision(request()))
                .isInstanceOf(AgentContractException.class)
                .hasMessageContaining("runId");
    }

    @Test
    void responseWithInventedSourceRefIsRejected() throws Exception {
        responseBody.set(Files.readString(
                FIXTURES.resolve("decision-response-invalid-invented-source-ref.json")));
        assertThatThrownBy(() -> engine.runDecision(request()))
                .isInstanceOf(AgentContractException.class);
    }

    @Test
    void unreachableBrainProducesTypedFailureWithoutRetry() {
        AgentBrainProperties properties = new AgentBrainProperties();
        properties.setBaseUrl("http://localhost:1"); // 该端口没有任何服务在监听
        properties.setConnectTimeoutMs(200);
        properties.setReadTimeoutSeconds(1);
        RemotePythonDecisionEngine isolated = new RemotePythonDecisionEngine(properties);
        Throwable failure = catchThrowable(() -> isolated.runDecision(request()));

        assertThat(failure).isInstanceOf(AgentBrainUnavailableException.class);
        assertThat(((AgentBrainUnavailableException) failure).failureCode())
                .isEqualTo(BrainFailureCode.BRAIN_UNAVAILABLE);
    }

    @Test
    void providerUnavailableResponseBecomesExplicitRemoteFailure() throws Exception {
        // 无类型的 5xx 保留历史的不透明失败码:引擎只对大脑明确报告的原因做分类。
        assertThat(failureCodeOf("{\"error\":\"provider unavailable\"}", 502))
                .isEqualTo(BrainFailureCode.BRAIN_UNAVAILABLE);
    }

    @Test
    void modelOutputContractViolationIsClassifiedAndNeverRetried() throws Exception {
        assertThat(failureCodeOf("{\"detail\":\"brain_failure:BrainContractError\"}", 502))
                .isEqualTo(BrainFailureCode.MODEL_CONTRACT_VIOLATION);
        // 确定性的契约违规绝不重试。
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void ambiguousSourceRefsIsAlsoAContractViolation() throws Exception {
        assertThat(failureCodeOf("{\"detail\":\"brain_failure:AmbiguousSourceRefsError\"}", 502))
                .isEqualTo(BrainFailureCode.MODEL_CONTRACT_VIOLATION);
    }

    @Test
    void ungroundedReferenceIsClassifiedSeparatelyFromAMalformedOutput() throws Exception {
        assertThat(failureCodeOf("{\"detail\":\"brain_failure:UngroundedReferenceError\"}", 502))
                .isEqualTo(BrainFailureCode.MODEL_UNGROUNDED_REFERENCE);
    }

    @Test
    void artifactUngroundedReferenceIsClassifiedToo() throws Exception {
        assertThat(failureCodeOf("{\"detail\":\"brain_failure:UngroundedReferenceError\"}", 502))
                .isEqualTo(BrainFailureCode.MODEL_UNGROUNDED_REFERENCE);
    }

    @Test
    void providerCallFailureInsideTheBrainIsItsOwnCode() throws Exception {
        assertThat(failureCodeOf("{\"detail\":\"brain_failure:ModelClientError\"}", 502))
                .isEqualTo(BrainFailureCode.MODEL_PROVIDER_FAILURE);
    }

    @Test
    void slowBrainIsReportedAsATimeoutNotAsAnUnreachableBrain() throws Exception {
        AgentBrainProperties properties = new AgentBrainProperties();
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort());
        properties.setInternalSecret("test-secret");
        properties.setConnectTimeoutMs(500);
        properties.setReadTimeoutSeconds(1);
        RemotePythonDecisionEngine impatient = new RemotePythonDecisionEngine(properties);
        latencyMillis.set(5_000L);
        responseBody.set(Files.readString(FIXTURES.resolve("decision-response-valid.json")));

        Throwable failure = catchThrowable(() -> impatient.runDecision(request()));

        assertThat(failure).isInstanceOf(AgentBrainUnavailableException.class);
        assertThat(((AgentBrainUnavailableException) failure).failureCode())
                .isEqualTo(BrainFailureCode.BRAIN_TIMEOUT);
    }

    @Test
    void v3IneligibleSelectionResponseBecomesTypedActionFailure() throws Exception {
        status.set(409);
        responseBody.set("{\"detail\":\"ACTION_INELIGIBLE\"}");

        assertThatThrownBy(() -> engine.runDecision(request()))
                .isInstanceOf(ActionIneligibleException.class)
                .hasMessageContaining("ACTION_INELIGIBLE");
    }

    @Test
    void brainFailureDetailClassificationIsExplicit() {
        assertThat(RemotePythonDecisionEngine.classifyBrainFailureDetail(
                "{\"detail\":\"brain_failure:BrainContractError\"}"))
                .isEqualTo(BrainFailureCode.MODEL_CONTRACT_VIOLATION);
        assertThat(RemotePythonDecisionEngine.classifyBrainFailureDetail(
                "{\"detail\":\"brain_failure:ArtifactBrainContractError\"}"))
                .isEqualTo(BrainFailureCode.MODEL_CONTRACT_VIOLATION);
        assertThat(RemotePythonDecisionEngine.classifyBrainFailureDetail(
                "{\"detail\":\"brain_failure:UngroundedReferenceError\"}"))
                .isEqualTo(BrainFailureCode.MODEL_UNGROUNDED_REFERENCE);
        assertThat(RemotePythonDecisionEngine.classifyBrainFailureDetail(
                "{\"detail\":\"brain_failure:ModelClientError\"}"))
                .isEqualTo(BrainFailureCode.MODEL_PROVIDER_FAILURE);
        assertThat(RemotePythonDecisionEngine.classifyBrainFailureDetail(
                "{\"detail\":\"brain_failure:BrokerTimeoutError\"}"))
                .isEqualTo(BrainFailureCode.BRAIN_TIMEOUT);
        assertThat(RemotePythonDecisionEngine.classifyBrainFailureDetail(
                "{\"detail\":\"brain_failure:ActionIneligibleBrainError\"}"))
                .isEqualTo(BrainFailureCode.BRAIN_UNAVAILABLE);
        assertThat(RemotePythonDecisionEngine.classifyBrainFailureDetail(null))
                .isEqualTo(BrainFailureCode.BRAIN_UNAVAILABLE);
        assertThat(RemotePythonDecisionEngine.classifyBrainFailureDetail("{} "))
                .isEqualTo(BrainFailureCode.BRAIN_UNAVAILABLE);
    }

    @Test
    void brokerTimeoutCallIsReportedAsATimeoutNotAsProviderFailure() throws Exception {
        // 大脑上报的 broker 超时必须映射为 BRAIN_TIMEOUT 而不是 MODEL_PROVIDER_FAILURE,
        // 这样用户才能看到正确的诊断。
        assertThat(failureCodeOf("{\"detail\":\"brain_failure:BrokerTimeoutError\"}", 502))
                .isEqualTo(BrainFailureCode.BRAIN_TIMEOUT);
    }
}
