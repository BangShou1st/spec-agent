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

    private static String modelOutputSchemaFailureBody() {
        return """
                {"detail":"brain_failure:ModelOutputContractError",
                 "contractDiagnostic":{"diagnosticId":"c44e8c96-f2b8-45c2-a0d2-8df16842cc78",
                    "stage":"brain.model_output_validation","validatorId":"ModelDecisionOutput",
                    "machineCode":"MODEL_OUTPUT_SCHEMA_INVALID",
                    "fieldPath":"action.payload.questionText","expectedType":"string",
                    "actualType":"object","boundary":"PYTHON_BRAIN",
                    "javaWireValidatorEntered":false,"executorReached":false,
                    "sourceExceptionType":"ValidationError"}}
                """;
    }

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
        wireGrants(engine);
    }

    private void wireGrants(RemotePythonDecisionEngine target) {
        var grants=org.mockito.Mockito.mock(com.specagent.agent.broker.WorkspaceRpcGrants.class);
        org.mockito.Mockito.when(grants.issue(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.any()))
            .thenReturn(new com.specagent.agent.broker.WorkspaceRpcGrants.Ticket("x".repeat(43)));
        target.setGrants(grants);
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
        Throwable failure = catchThrowable(() -> engine.runDecision(request()));
        assertThat(failure).isInstanceOf(ModelResponseContractException.class);
        ModelContractDiagnostic diagnostic = ((ModelResponseContractException) failure).diagnostic();
        assertThat(diagnostic.machineCode()).isEqualTo("JAVA_WIRE_CONTRACT_INVALID");
        assertThat(diagnostic.fieldPath()).isEqualTo("UNKNOWN");
        assertThat(diagnostic.expectedType()).isEqualTo("unknown");
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
        wireGrants(isolated);
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
        assertThat(failureCodeOf(modelOutputSchemaFailureBody(), 502))
                .isEqualTo(BrainFailureCode.MODEL_CONTRACT_VIOLATION);
        // 确定性的契约违规绝不重试。
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test
    void structuredBrainContractDiagnosticSurvivesHttpBoundaryWithoutPrivateValues() throws Exception {
        String body = modelOutputSchemaFailureBody();
        status.set(502);
        responseBody.set(body);

        Throwable failure = catchThrowable(() -> engine.runDecision(request()));

        assertThat(failure).isInstanceOf(AgentBrainUnavailableException.class);
        AgentBrainUnavailableException typed = (AgentBrainUnavailableException) failure;
        assertThat(typed.failureCode()).isEqualTo(BrainFailureCode.MODEL_CONTRACT_VIOLATION);
        assertThat(typed.diagnostic()).isNotNull();
        assertThat(typed.diagnostic().diagnosticId())
                .isEqualTo("c44e8c96-f2b8-45c2-a0d2-8df16842cc78");
        assertThat(typed.diagnostic().machineCode()).isEqualTo("MODEL_OUTPUT_SCHEMA_INVALID");
        assertThat(typed.diagnostic().fieldPath()).isEqualTo("action.payload.questionText");
        assertThat(typed.diagnostic().expectedType()).isEqualTo("string");
        assertThat(typed.diagnostic().actualType()).isEqualTo("object");
        assertThat(typed.diagnostic().executorReached()).isFalse();
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
        wireGrants(impatient);
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
                .isEqualTo(BrainFailureCode.BRAIN_UNAVAILABLE);
        assertThat(RemotePythonDecisionEngine.classifyBrainFailureDetail(
                "{\"detail\":\"brain_failure:ArtifactBrainContractError\"}"))
                .isEqualTo(BrainFailureCode.BRAIN_UNAVAILABLE);
        assertThat(RemotePythonDecisionEngine.classifyBrainFailureDetail(
                "{\"detail\":\"brain_failure:ModelOutputContractError\"}"))
                .isEqualTo(BrainFailureCode.BRAIN_UNAVAILABLE);
        assertThat(RemotePythonDecisionEngine.classifyBrainFailureDetail(
                modelOutputSchemaFailureBody()))
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
    void genericJavaContractExceptionOutsideResponseBoundaryIsNotAProvenModelFailure() {
        assertThat(com.specagent.agent.runtime.RunFailureReasons.reasonCode(
                new AgentContractException("contract failure outside response boundary")))
                .isEqualTo("AgentContractException");
    }

    @Test
    void responseWireContractFailureCarriesSanitizedBoundaryAndFieldMetadata() throws Exception {
        responseBody.set("""
                {"protocolVersion":"agent-decision.v2","runId":"22222222-2222-2222-2222-222222222222",
                 "mysteryField":"PRIVATE_VALUE_MUST_NOT_PERSIST","stateUpdate":null,
                 "observation":null,"actionProposal":null,
                 "usage":{"modelCalls":0,"promptHashes":[]},"diagnostics":{}}
                """);

        Throwable failure = catchThrowable(() -> engine.runDecision(request()));

        assertThat(failure).isInstanceOf(ModelResponseContractException.class);
        ModelResponseContractException typed = (ModelResponseContractException) failure;
        assertThat(typed.diagnostic().boundary()).isEqualTo("JAVA_WIRE");
        assertThat(typed.diagnostic().stage()).isEqualTo("java.response_wire_validation");
        assertThat(typed.diagnostic().machineCode()).isEqualTo("JAVA_WIRE_UNKNOWN_FIELD");
        assertThat(typed.diagnostic().fieldPath()).isEqualTo("UNKNOWN_FIELD");
        assertThat(typed.diagnostic().actualType()).isEqualTo("present");
        assertThat(typed.diagnostic().sourceExceptionType())
                .isEqualTo("UnrecognizedPropertyException");
        assertThat(typed.getMessage()).doesNotContain("PRIVATE_VALUE_MUST_NOT_PERSIST");
    }

    @Test
    void javaResponseValidatorRecordsFieldPathAndTypesWithoutPayloadValue() throws Exception {
        String valid = Files.readString(FIXTURES.resolve("decision-response-valid.json"));
        responseBody.set(valid.replace(
                "\"questionText\": \"除了减少邮件沟通之外，新系统最重要的目标是什么？\"",
                "\"questionText\": {\"privateValue\": \"PRIVATE_VALUE_MUST_NOT_PERSIST\"}"));

        Throwable failure = catchThrowable(() -> engine.runDecision(request()));

        assertThat(failure).isInstanceOf(ModelResponseContractException.class);
        ModelContractDiagnostic diagnostic =
                ((ModelResponseContractException) failure).diagnostic();
        assertThat(diagnostic.stage()).isEqualTo("java.response_contract_validation");
        assertThat(diagnostic.validatorId()).isEqualTo("AgentBrainResponseValidator");
        assertThat(diagnostic.machineCode()).isEqualTo("JAVA_RESPONSE_CONTRACT_INVALID");
        assertThat(diagnostic.fieldPath()).isEqualTo("actionProposal.payload.questionText");
        assertThat(diagnostic.expectedType()).isEqualTo("string");
        assertThat(diagnostic.actualType()).isEqualTo("object");
        assertThat(diagnostic.boundary()).isEqualTo("JAVA_RESPONSE_VALIDATOR");
        assertThat(diagnostic.javaWireValidatorEntered()).isTrue();
        assertThat(diagnostic.executorReached()).isFalse();
        assertThat(failure.getMessage()).doesNotContain("PRIVATE_VALUE_MUST_NOT_PERSIST");
    }

    @Test
    void malformedOrIncompatibleBrainDiagnosticDoesNotBecomeAContractCode() {
        assertThat(RemotePythonDecisionEngine.classifyBrainFailureDetail("""
                {"detail":{"marker":"brain_failure","errorType":"ModelOutputContractError",
                 "diagnostic":{"machineCode":"MODEL_OUTPUT_UNGROUNDED_REFERENCE"}}}
                """))
                .isEqualTo(BrainFailureCode.BRAIN_UNAVAILABLE);
        assertThat(RemotePythonDecisionEngine.classifyBrainFailureDetail("""
                {"detail":{"marker":"brain_failure","errorType":"ModelOutputContractError"}}
                """))
                .isEqualTo(BrainFailureCode.BRAIN_UNAVAILABLE);
    }

    @Test
    void wireTypeMismatchReportsPathAndTypesWithoutValues() throws Exception {
        responseBody.set("""
                {"protocolVersion":"agent-decision.v2","runId":["PRIVATE_ID"],
                 "stateUpdate":null,"observation":null,"actionProposal":null,
                 "usage":{"modelCalls":0,"promptHashes":[]},"diagnostics":{}}
                """);

        Throwable failure = catchThrowable(() -> engine.runDecision(request()));

        assertThat(failure).isInstanceOf(ModelResponseContractException.class);
        ModelContractDiagnostic diagnostic = ((ModelResponseContractException) failure).diagnostic();
        assertThat(diagnostic.machineCode()).isEqualTo("JAVA_WIRE_TYPE_MISMATCH");
        assertThat(diagnostic.fieldPath()).isEqualTo("runId");
        assertThat(diagnostic.expectedType()).isEqualTo("uuid");
        assertThat(diagnostic.actualType()).isEqualTo("array");
        assertThat(diagnostic.sourceExceptionType()).isEqualTo("MismatchedInputException");
        assertThat(failure.getMessage()).doesNotContain("PRIVATE_ID");
    }

    @Test
    void brokerTimeoutCallIsReportedAsATimeoutNotAsProviderFailure() throws Exception {
        // 大脑上报的 broker 超时必须映射为 BRAIN_TIMEOUT 而不是 MODEL_PROVIDER_FAILURE,
        // 这样用户才能看到正确的诊断。
        assertThat(failureCodeOf("{\"detail\":\"brain_failure:BrokerTimeoutError\"}", 502))
                .isEqualTo(BrainFailureCode.BRAIN_TIMEOUT);
    }

    // -- 端到端:HTTP 502 detail -> 类型化失败码 -> RUN_FAILED reason -> 评测分类 ----

    /**
     * 完整跨边界链路:Python Brain 的 502 detail 被解析成 {@link BrainFailureCode},
     * 运行时把它归一成 {@code RUN_FAILED} 的稳定机器码,评测再按该机器码分类。
     * 这是"C08/C15 的 Brain 失败应被正确归因"所需的唯一一条链路——
     * <b>不对任何 502 做整体猜测</b>:只有 Brain 明确声明的类型才有明确分类,
     * 其余一律维持 provider/unavailable 或 unknown。
     */
    @Test
    void typedBrainFailureSurvivesTheBoundaryAndClassifiesInEval() {
        // 大脑输出契约违规 -> 模型协议违规。
        assertThat(evalClassification("brain_failure:DecisionBrainContractError"))
                .isEqualTo(com.specagent.eval.P3BDecisionQuality
                        .FailureClassification.PROVIDER_UNAVAILABLE);
        assertThat(evalClassification("brain_failure:AmbiguousSourceRefsError"))
                .isEqualTo(com.specagent.eval.P3BDecisionQuality
                        .FailureClassification.MODEL_PROTOCOL_VIOLATION);
        // 无依据引用 -> 同样是模型协议违规(来源被拦截)。
        assertThat(evalClassification("brain_failure:UngroundedReferenceError"))
                .isEqualTo(com.specagent.eval.P3BDecisionQuality
                        .FailureClassification.MODEL_PROTOCOL_VIOLATION);
        // 大脑内部的模型调用失败 -> 提供商分类。
        assertThat(evalClassification("brain_failure:ModelClientError"))
                .isEqualTo(com.specagent.eval.P3BDecisionQuality
                        .FailureClassification.PROVIDER_UNAVAILABLE);
        // 传输超时 -> 超时分类,绝不与"Brain 宕机"混同。
        assertThat(evalClassification("brain_failure:BrokerTimeoutError"))
                .isEqualTo(com.specagent.eval.P3BDecisionQuality
                        .FailureClassification.TIMEOUT);
    }

    /** 无法说明成因的 502 绝不冒充模型协议错误:只落到 provider 分类。 */
    @Test
    void anUnattributed502IsNeverReportedAsAModelContractViolation() {
        assertThat(evalClassification(null))
                .isEqualTo(com.specagent.eval.P3BDecisionQuality
                        .FailureClassification.PROVIDER_UNAVAILABLE);
        assertThat(evalClassification("provider unavailable"))
                .isEqualTo(com.specagent.eval.P3BDecisionQuality
                        .FailureClassification.PROVIDER_UNAVAILABLE);
    }

    /**
     * 未知的 Brain 错误类型退化为不透明的 {@code brain_unavailable} 失败码(引擎绝不
     * 臆造成因),评测据此把它当作 provider 侧的不可达/未归类失败——这类失败
     * <b>不计入</b>任何质量比率的分母。真正"完全没有失败证据"的情形才是
     * {@code UNKNOWN_FAILURE}(见评测侧用例)。
     */
    @Test
    void anUnknownBrainErrorTypeDegradesToAnOpaqueProviderFailure() {
        assertThat(evalClassification("brain_failure:SomeBrandNewError"))
                .isEqualTo(com.specagent.eval.P3BDecisionQuality
                        .FailureClassification.PROVIDER_UNAVAILABLE);
    }

    private static com.specagent.eval.P3BDecisionQuality.FailureClassification evalClassification(
            String detail) {
        String body = detail == null ? "{}" : "{\"detail\":\"" + detail + "\"}";
        AgentBrainUnavailableException failure = new AgentBrainUnavailableException(
                "Agent brain call failed: /v1/decisions", null,
                RemotePythonDecisionEngine.classifyBrainFailureDetail(body));
        String reason = com.specagent.agent.runtime.RunFailureReasons.reasonCode(failure);
        return com.specagent.eval.P3BDecisionQuality.classifyFailure(
                "failed:" + reason, null, null, null, null);
    }
}
