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
 * Default decision engine: the remote Python {@code agent-brain} service.
 *
 * <p>Sends the frozen request envelope, parses the response with the strict
 * contract mapper (unknown fields/versions fail closed), and validates it
 * through {@link AgentBrainResponseValidator} before returning. Transport
 * failures become typed {@link AgentBrainUnavailableException}s; no retry and
 * no fallback happens here.
 */
@Component
@ConditionalOnProperty(name = "spec.agent.brain.engine", havingValue = "remote-python")
public class RemotePythonDecisionEngine implements AgentDecisionEngine {

    /** The brain's typed detail prefix: {@code brain_failure:<ErrorType>}. */
    static final String BRAIN_FAILURE_MARKER = "brain_failure:";

    /** Brain error types that mean "the model output violated a contract". */
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

    /** Typed 5xx/409 failure: the code comes from the brain's own detail. */
    private static AgentBrainUnavailableException brainFailure(
            String path, HttpStatusCodeException ex) {
        return new AgentBrainUnavailableException(
                "Agent brain call failed: " + path, ex,
                classifyBrainFailureDetail(ex.getResponseBodyAsString()));
    }

    /**
     * Typed transport failure: a real timeout is not "the brain is down".
     *
     * <p>A read timeout does not always arrive as
     * {@code ResourceAccessException} — the response-extraction path wraps
     * {@link SocketTimeoutException} in a plain {@code RestClientException} —
     * so the timeout is detected from the cause chain instead of the type.
     */
    private static AgentBrainUnavailableException brainFailure(
            String path, RestClientException ex) {
        return new AgentBrainUnavailableException(
                "Agent brain call failed: " + path, ex,
                isTimeout(ex) ? BrainFailureCode.BRAIN_TIMEOUT
                        : BrainFailureCode.BRAIN_UNAVAILABLE);
    }

    /**
     * Maps the brain's typed failure detail onto a failure code.
     *
     * <p>Only the error type the brain actually named is classified; an absent
     * or unrecognized detail stays the historical opaque code, so the engine
     * never invents a cause it cannot prove.
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
        // Explicit allow-list of the brain's own output-contract error types:
        // only a type the brain can actually raise is classified, so a rename or
        // a new type degrades to the opaque code instead of a wrong diagnosis.
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
