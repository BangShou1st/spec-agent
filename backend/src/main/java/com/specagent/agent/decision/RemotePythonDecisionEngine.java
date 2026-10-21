package com.specagent.agent.decision;

import com.specagent.agent.protocol.ActionEligibilityReasonCode;

import com.specagent.agent.broker.AgentBrainProperties;
import com.specagent.agent.protocol.AgentArtifactResponse;
import com.specagent.agent.protocol.AgentContracts;
import com.specagent.agent.protocol.AgentProtocol;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.AgentResponseEnvelope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.HttpStatusCodeException;
import com.specagent.agent.protocol.ActionIneligibleException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;
import java.util.UUID;

/**
 * 文件名:RemotePythonDecisionEngine.java
 *
 * 用途:默认决策引擎,即远程的 Python {@code agent-brain} 服务。
 *
 * 发送冻结的请求信封,用严格的契约 mapper 解析响应(未知字段/版本一律
 * fail-closed),并在返回前先通过 {@link AgentBrainResponseValidator} 校验。
 * 传输失败会转换为类型化的 {@link AgentBrainUnavailableException};此处
 * 不做任何重试,也没有降级兜底。
 */
@Component
@ConditionalOnProperty(name = "spec.agent.brain.engine", havingValue = "remote-python")
public class RemotePythonDecisionEngine implements AgentDecisionEngine {

    /** Brain 的类型化 detail 前缀:{@code brain_failure:<ErrorType>}。 */
    static final String BRAIN_FAILURE_MARKER = "brain_failure:";

    private static final ObjectMapper FAILURE_JSON = new ObjectMapper();
    private static final java.util.Set<String> MODEL_OUTPUT_DIAGNOSTIC_CODES = java.util.Set.of(
            "MODEL_OUTPUT_INVALID_JSON", "MODEL_OUTPUT_SCHEMA_INVALID",
            "MODEL_OUTPUT_AMBIGUOUS_SOURCE_REFS", "MODEL_OUTPUT_UNGROUNDED_REFERENCE",
            "MODEL_OUTPUT_CONFLICT_NOT_SURFACED");

    private final RestClient restClient;
    private com.specagent.agent.broker.WorkspaceRpcGrants grants;
    private final int readTimeout;
    @org.springframework.beans.factory.annotation.Autowired
    public void setGrants(com.specagent.agent.broker.WorkspaceRpcGrants grants) {this.grants=grants;}

    public RemotePythonDecisionEngine(AgentBrainProperties properties) {
        this.readTimeout=properties.getReadTimeoutSeconds();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(properties.getConnectTimeoutMs());
        factory.setReadTimeout(properties.getReadTimeoutSeconds() * 1000);
        this.restClient = RestClient.builder()
                .baseUrl(properties.getBaseUrl())
                .requestFactory(factory)
                .defaultHeader(AgentProtocol.INTERNAL_TOKEN_HEADER, properties.getInternalSecret())
                .build();
    }

    @Override
    public AgentResponseEnvelope runStateUpdate(AgentRequestEnvelope request) {
        return call(request, "/v1/state-updates", true);
    }

    @Override
    public AgentResponseEnvelope runDecision(AgentRequestEnvelope request) {
        return call(request, "/v1/decisions", false);
    }

    @Override
    public AgentArtifactResponse runArtifactGeneration(AgentRequestEnvelope request) {
        String responseJson;
        var ticket=issue(request,"ARTIFACT_GENERATION",1);
        try {
            responseJson = restClient.post()
                    .uri("/v1/artifacts")
                    .header(com.specagent.agent.broker.WorkspaceRpcGrants.HEADER,ticket.value())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(AgentContracts.write(request))
                    .retrieve()
                    .body(String.class);
        } catch (HttpServerErrorException ex) {
            throw brainFailure("/v1/artifacts", ex);
        } catch (RestClientException ex) {
            throw brainFailure("/v1/artifacts", ex);
        } finally {
            grants.revoke(ticket);
        }
        if (responseJson == null || responseJson.isBlank()) {
            throw new AgentBrainUnavailableException(
                    "Agent brain returned an empty response: /v1/artifacts", null);
        }
        AgentArtifactResponse response;
        try {
            response = AgentContracts.read(responseJson, AgentArtifactResponse.class);
        } catch (com.specagent.agent.protocol.AgentContractException ex) {
            throw responseContractFailure(ex, true);
        }
        try {
            AgentBrainResponseValidator.validateArtifact(request, response);
        } catch (com.specagent.agent.protocol.AgentContractException ex) {
            throw responseContractFailure(ex, false);
        }
        return response;
    }

    /** 类型化的 5xx/409 失败:失败码取自 Brain 自己的 detail。 */
    private static AgentBrainUnavailableException brainFailure(
            String path, HttpStatusCodeException ex) {
        String responseBody = ex.getResponseBodyAsString();
        BrainFailureCode code = classifyBrainFailureDetail(responseBody);
        return new AgentBrainUnavailableException(
                "Agent brain call failed: " + path, ex,
                code, modelContractDiagnostic(responseBody, code));
    }

    /**
     * 类型化的传输失败:真正的超时不等于"Brain 宕机"。
     *
     * 读取超时并不总是以 {@code ResourceAccessException} 的形式出现——
     * 响应提取路径会把 {@link SocketTimeoutException} 包在普通的
     * {@code RestClientException} 里——所以超时要从 cause 链检测,
     * 而不是靠异常类型判断。
     */
    private static AgentBrainUnavailableException brainFailure(
            String path, RestClientException ex) {
        return new AgentBrainUnavailableException(
                "Agent brain call failed: " + path, ex,
                isTimeout(ex) ? BrainFailureCode.BRAIN_TIMEOUT
                        : BrainFailureCode.BRAIN_UNAVAILABLE);
    }

    /**
     * 把 Brain 的类型化失败 detail 映射为失败码。
     *
     * 只对 Brain 实际声明的错误类型做归类;detail 缺失或无法识别时保持
     * 历史的不透明失败码,引擎绝不臆造一个无法证实的成因。
     */
    static BrainFailureCode classifyBrainFailureDetail(String responseBody) {
        FailureDetail detail = parseFailureDetail(responseBody);
        if (detail == null) return BrainFailureCode.BRAIN_UNAVAILABLE;
        if ("UngroundedReferenceError".equals(detail.errorType())) {
            return BrainFailureCode.MODEL_UNGROUNDED_REFERENCE;
        }
        if ("ModelClientError".equals(detail.errorType())) {
            return BrainFailureCode.MODEL_PROVIDER_FAILURE;
        }
        if ("BrokerTimeoutError".equals(detail.errorType())) {
            return BrainFailureCode.BRAIN_TIMEOUT;
        }
        if ("ModelOutputContractError".equals(detail.errorType())) {
            return compatibleModelDiagnostic(detail.errorType(), detail.machineCode())
                    ? BrainFailureCode.MODEL_CONTRACT_VIOLATION
                    : BrainFailureCode.BRAIN_UNAVAILABLE;
        }
        if ("AmbiguousSourceRefsError".equals(detail.errorType())
                || "ConflictSurfacingError".equals(detail.errorType())) {
            return BrainFailureCode.MODEL_CONTRACT_VIOLATION;
        }
        return BrainFailureCode.BRAIN_UNAVAILABLE;
    }

    private static ModelContractDiagnostic modelContractDiagnostic(
            String body, BrainFailureCode code) {
        if (code != BrainFailureCode.MODEL_CONTRACT_VIOLATION
                && code != BrainFailureCode.MODEL_UNGROUNDED_REFERENCE) return null;
        FailureDetail detail = parseFailureDetail(body);
        if (detail == null || detail.diagnostic() == null) return null;
        JsonNode diagnostic = detail.diagnostic();
        String machineCode = text(diagnostic, "machineCode");
        if (!compatibleModelDiagnostic(detail.errorType(), machineCode)) return null;
        return new ModelContractDiagnostic(
                text(diagnostic, "diagnosticId"),
                text(diagnostic, "stage"),
                text(diagnostic, "validatorId"),
                machineCode,
                text(diagnostic, "fieldPath"),
                text(diagnostic, "expectedType"),
                text(diagnostic, "actualType"),
                text(diagnostic, "boundary"),
                nullableBoolean(diagnostic, "javaWireValidatorEntered"),
                nullableBoolean(diagnostic, "executorReached"),
                text(diagnostic, "sourceExceptionType"));
    }

    private static FailureDetail parseFailureDetail(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            JsonNode root = FAILURE_JSON.readTree(body);
            JsonNode detail = root == null ? null : root.get("detail");
            if (detail != null && detail.isObject()) {
                if (!"brain_failure".equals(text(detail, "marker"))) return null;
                JsonNode diagnostic = detail.get("diagnostic");
                if (diagnostic == null || !diagnostic.isObject()) {
                    diagnostic = root.get("contractDiagnostic");
                }
                return new FailureDetail(text(detail, "errorType"),
                        diagnostic != null && diagnostic.isObject() ? diagnostic : null,
                        text(diagnostic, "machineCode"));
            }
            if (detail != null && detail.isTextual()) {
                String legacy = detail.asText();
                int marker = legacy.indexOf(BRAIN_FAILURE_MARKER);
                if (marker < 0) return null;
                String errorType = legacy.substring(marker + BRAIN_FAILURE_MARKER.length())
                        .split("[^A-Za-z0-9_]", 2)[0];
                JsonNode diagnostic = root.get("contractDiagnostic");
                return errorType.isBlank() ? null : new FailureDetail(errorType,
                        diagnostic != null && diagnostic.isObject() ? diagnostic : null,
                        text(diagnostic, "machineCode"));
            }
        } catch (Exception ignored) {
            // Unreadable or incompatible failures remain opaque.
        }
        return null;
    }

    private static String text(JsonNode node, String name) {
        if (node == null) return null;
        JsonNode value = node.get(name);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static Boolean nullableBoolean(JsonNode node, String name) {
        if (node == null) return null;
        JsonNode value = node.get(name);
        return value != null && value.isBoolean() ? value.booleanValue() : null;
    }

    private record FailureDetail(String errorType, JsonNode diagnostic, String machineCode) {
    }

    private static boolean isTimeout(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof SocketTimeoutException
                    || cause instanceof TimeoutException
                    || cause.getClass().getSimpleName().contains("Timeout")) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private AgentResponseEnvelope call(AgentRequestEnvelope request,
                                         String path,
                                         boolean stateUpdate) {
        String responseJson;
        var ticket=issue(request,stateUpdate?"STATE_UPDATE":"DECISION",stateUpdate?1:
                request.decisionBudget()==null?1:Math.min(2,request.decisionBudget().maxModelCalls()));
        try {
            responseJson = restClient.post()
                    .uri(path)
                    .header(com.specagent.agent.broker.WorkspaceRpcGrants.HEADER,ticket.value())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(AgentContracts.write(request))
                    .retrieve()
                    .body(String.class);
        } catch (HttpClientErrorException.Conflict ex) {
            if (!stateUpdate) {
                throw new ActionIneligibleException(
                        ActionEligibilityReasonCode.FAMILY_NOT_ELIGIBLE);
            }
            throw brainFailure(path, ex);
        } catch (HttpServerErrorException ex) {
            throw brainFailure(path, ex);
        } catch (RestClientException ex) {
            throw brainFailure(path, ex);
        } finally {
            grants.revoke(ticket);
        }
        if (responseJson == null || responseJson.isBlank()) {
            throw new AgentBrainUnavailableException(
                    "Agent brain returned an empty response: " + path, null);
        }
        AgentResponseEnvelope response;
        try {
            response = AgentContracts.read(responseJson, AgentResponseEnvelope.class);
        } catch (com.specagent.agent.protocol.AgentContractException ex) {
            throw responseContractFailure(ex, true);
        }
        if (stateUpdate) {
            try {
                AgentBrainResponseValidator.validateStateUpdate(request, response);
            } catch (com.specagent.agent.protocol.AgentContractException ex) {
                throw responseContractFailure(ex, false);
            }
        } else {
            try {
                AgentBrainResponseValidator.validateDecision(request, response);
            } catch (com.specagent.agent.protocol.AgentContractException ex) {
                throw responseContractFailure(ex, false);
            }
        }
        return response;
    }

    private static ModelResponseContractException responseContractFailure(
            com.specagent.agent.protocol.AgentContractException failure, boolean wire) {
        String boundary = wire ? "JAVA_WIRE" : "JAVA_RESPONSE_VALIDATOR";
        String stage = wire ? "java.response_wire_validation"
                : "java.response_contract_validation";
        String code = failure.diagnosticCode();
        if (code == null || code.isBlank()) {
            code = wire ? "JAVA_WIRE_CONTRACT_INVALID" : "JAVA_RESPONSE_CONTRACT_INVALID";
        }
        String path = failure.fieldPath();
        String validator = failure.validatorId();
        if (validator == null || validator.isBlank()) {
            validator = wire ? "AgentContracts.read" : "AgentBrainResponseValidator";
        }
        ModelContractDiagnostic diagnostic = new ModelContractDiagnostic(
                failure.diagnosticId(), stage, validator, code, path,
                failure.expectedType(), failure.actualType(), boundary,
                true, false, failure.sourceExceptionType() == null
                        ? failure.getClass().getSimpleName() : failure.sourceExceptionType());
        return new ModelResponseContractException(diagnostic);
    }

    private static boolean compatibleModelDiagnostic(String errorType, String machineCode) {
        if (errorType == null || machineCode == null
                || !MODEL_OUTPUT_DIAGNOSTIC_CODES.contains(machineCode)) return false;
        return switch (errorType) {
            case "ModelOutputContractError" -> machineCode.equals("MODEL_OUTPUT_INVALID_JSON")
                    || machineCode.equals("MODEL_OUTPUT_SCHEMA_INVALID");
            case "AmbiguousSourceRefsError" ->
                    machineCode.equals("MODEL_OUTPUT_AMBIGUOUS_SOURCE_REFS");
            case "UngroundedReferenceError" ->
                    machineCode.equals("MODEL_OUTPUT_UNGROUNDED_REFERENCE");
            case "ConflictSurfacingError" ->
                    machineCode.equals("MODEL_OUTPUT_CONFLICT_NOT_SURFACED");
            default -> false;
        };
    }

    private com.specagent.agent.broker.WorkspaceRpcGrants.Ticket issue(AgentRequestEnvelope request,String type,int maximum) {
        if(grants==null) throw new IllegalStateException("Workspace RPC grant issuer is required");
        return grants.issue(request.runId(),type,maximum,java.time.Duration.ofSeconds(Math.min(180,Math.max(1,readTimeout+5))),request.snapshot());
    }
}
