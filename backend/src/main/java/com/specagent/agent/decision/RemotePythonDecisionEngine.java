package com.specagent.agent.decision;

import com.specagent.agent.decision.RemotePythonDecisionEngine;
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
import com.specagent.agent.protocol.ActionEligibilityReasonCode;
import com.specagent.agent.protocol.ActionIneligibleException;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeoutException;

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

    /** 表示"模型输出违反了契约"的 Brain 错误类型。 */
    private static final java.util.List<String> BRAIN_CONTRACT_ERROR_TYPES = java.util.List.of(
            "Contract",
            "AmbiguousSourceRefs",
            "ConflictSurfacing");

    private final RestClient restClient;

    public RemotePythonDecisionEngine(AgentBrainProperties properties) {
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
        try {
            responseJson = restClient.post()
                    .uri("/v1/artifacts")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(AgentContracts.write(request))
                    .retrieve()
                    .body(String.class);
        } catch (HttpServerErrorException ex) {
            throw brainFailure("/v1/artifacts", ex);
        } catch (RestClientException ex) {
            throw brainFailure("/v1/artifacts", ex);
        }
        if (responseJson == null || responseJson.isBlank()) {
            throw new AgentBrainUnavailableException(
                    "Agent brain returned an empty response: /v1/artifacts", null);
        }
        AgentArtifactResponse response =
                AgentContracts.read(responseJson, AgentArtifactResponse.class);
        AgentBrainResponseValidator.validateArtifact(request, response);
        return response;
    }

    /** 类型化的 5xx/409 失败:失败码取自 Brain 自己的 detail。 */
    private static AgentBrainUnavailableException brainFailure(
            String path, HttpStatusCodeException ex) {
        return new AgentBrainUnavailableException(
                "Agent brain call failed: " + path, ex,
                classifyBrainFailureDetail(ex.getResponseBodyAsString()));
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
        if (responseBody == null) {
            return BrainFailureCode.BRAIN_UNAVAILABLE;
        }
        int marker = responseBody.indexOf(BRAIN_FAILURE_MARKER);
        if (marker < 0) {
            return BrainFailureCode.BRAIN_UNAVAILABLE;
        }
        String errorType = responseBody.substring(marker + BRAIN_FAILURE_MARKER.length())
                .split("[^A-Za-z0-9_]", 2)[0];
        if (errorType.contains("UngroundedReference")) {
            return BrainFailureCode.MODEL_UNGROUNDED_REFERENCE;
        }
        if (errorType.contains("ModelClientError")) {
            return BrainFailureCode.MODEL_PROVIDER_FAILURE;
        }
        if (errorType.contains("BrokerTimeoutError")) {
            return BrainFailureCode.BRAIN_TIMEOUT;
        }
        // 对 Brain 自身输出契约错误类型的显式白名单:只归类 Brain 真正可能
        // 抛出的类型,这样 Brain 侧改名或新增类型只会退化为不透明失败码,
        // 而不会得到错误的诊断。
        for (String contractError : BRAIN_CONTRACT_ERROR_TYPES) {
            if (errorType.contains(contractError)) {
                return BrainFailureCode.MODEL_CONTRACT_VIOLATION;
            }
        }
        return BrainFailureCode.BRAIN_UNAVAILABLE;
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
        try {
            responseJson = restClient.post()
                    .uri(path)
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
        }
        if (responseJson == null || responseJson.isBlank()) {
            throw new AgentBrainUnavailableException(
                    "Agent brain returned an empty response: " + path, null);
        }
        AgentResponseEnvelope response =
                AgentContracts.read(responseJson, AgentResponseEnvelope.class);
        if (stateUpdate) {
            AgentBrainResponseValidator.validateStateUpdate(request, response);
        } else {
            AgentBrainResponseValidator.validateDecision(request, response);
        }
        return response;
    }
}
