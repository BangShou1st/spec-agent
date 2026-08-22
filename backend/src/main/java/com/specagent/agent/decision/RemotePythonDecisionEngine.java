package com.specagent.agent.decision;

import com.specagent.agent.runtime.AgentBrainProperties;
import com.specagent.agent.contract.AgentContracts;
import com.specagent.agent.contract.AgentProtocol;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AgentResponseEnvelope;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

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
        } catch (RestClientException ex) {
            throw new AgentBrainUnavailableException(
                    "Agent brain call failed: " + path, ex);
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
