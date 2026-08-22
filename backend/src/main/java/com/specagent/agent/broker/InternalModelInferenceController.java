package com.specagent.agent.broker;

import com.specagent.agent.runtime.AgentBrainProperties;
import com.specagent.agent.contract.AgentContracts;
import com.specagent.agent.contract.AgentProtocol;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.contract.AgentContractException;
import com.specagent.model.gateway.ModelGatewayException;
import com.specagent.model.inference.ModelInferenceGateway;
import com.specagent.model.inference.ModelInferenceMessage;
import com.specagent.model.inference.ModelInferenceRequest;
import com.specagent.model.inference.ModelInferenceResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Internal, authenticated model inference broker for the Python agent brain.
 *
 * <p>This is not a product API. The brain calls it so provider transport is
 * never duplicated into Python: credentials, model selection and the frozen
 * OpenCode transport stay Java-side, and Python never receives a key.
 *
 * <p>Safety requirements enforced here: shared internal secret (constant-time
 * compare), requests tied to a {@code runId} and closed call-type set, bounded
 * prompt size, no arbitrary URL/header forwarding, no provider fallback, no
 * hidden retry, and sanitized AgentRun events (call type + hashes only).
 */
@RestController
@RequestMapping("/internal/v1/model-inference")
public class InternalModelInferenceController {

    private static final Logger LOG = LoggerFactory.getLogger(InternalModelInferenceController.class);

    private static final Set<String> ALLOWED_ROLES = Set.of("system", "user");

    private final ModelInferenceGateway gateway;
    private final AgentRunEventService eventService;
    private final AgentBrainProperties properties;

    public InternalModelInferenceController(ModelInferenceGateway gateway,
                                            AgentRunEventService eventService,
                                            AgentBrainProperties properties) {
        this.gateway = gateway;
        this.eventService = eventService;
        this.properties = properties;
    }

    @PostMapping
    public ResponseEntity<ModelInferenceHttpResponse> complete(
            @RequestHeader(value = AgentProtocol.INTERNAL_TOKEN_HEADER, required = false)
            String internalToken,
            @RequestBody String body) {
        if (!tokenMatches(internalToken)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }

        ModelInferenceHttpRequest request;
        try {
            request = AgentContracts.read(body, ModelInferenceHttpRequest.class);
            validate(request);
        } catch (AgentContractException ex) {
            LOG.warn("Internal inference broker rejected request: {}", ex.getMessage());
            return ResponseEntity.badRequest().build();
        }

        long startedAt = System.nanoTime();
        ModelInferenceResponse response;
        try {
            response = gateway.complete(new ModelInferenceRequest(
                    request.runId(),
                    request.callType(),
                    request.messages().stream()
                            .map(message -> new ModelInferenceMessage(message.role(), message.content()))
                            .toList(),
                    request.maxOutputTokens()));
        } catch (ModelGatewayException ex) {
            recordFailure(request, ex);
            // Provider-neutral category only; provider payloads never leave here.
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(new ModelInferenceHttpResponse(
                    AgentProtocol.INFERENCE_PROTOCOL_VERSION, "", "error", null));
        }
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("callType", request.callType());
        payload.put("promptSha256", sha256(concatMessages(request)));
        payload.put("outputSha256", sha256(response.content()));
        payload.put("messageCount", request.messages().size());
        payload.put("finishReason", response.finishReason());
        payload.put("elapsedMillis", elapsedMillis);
        eventService.append(request.runId(), phaseFor(request.callType()),
                "MODEL_INFERENCE", payload);

        return ResponseEntity.ok(new ModelInferenceHttpResponse(
                AgentProtocol.INFERENCE_PROTOCOL_VERSION,
                response.content(),
                response.finishReason(),
                new ModelInferenceHttpResponse.Usage(response.promptTokens(), response.completionTokens())));
    }

    private void recordFailure(ModelInferenceHttpRequest request, ModelGatewayException ex) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("callType", request.callType());
        payload.put("gatewayCategory", ex.gatewayCategory());
        eventService.append(request.runId(), phaseFor(request.callType()),
                "MODEL_INFERENCE_FAILED", payload);
    }

    private AgentRunPhase phaseFor(String callType) {
        return "DECISION".equals(callType) ? AgentRunPhase.DECIDING : AgentRunPhase.STATE_UPDATING;
    }

    private boolean tokenMatches(String provided) {
        String expected = properties.getInternalSecret();
        if (expected == null || expected.isBlank() || provided == null || provided.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                provided.getBytes(StandardCharsets.UTF_8));
    }

    private void validate(ModelInferenceHttpRequest request) {
        if (!AgentProtocol.INFERENCE_PROTOCOL_VERSION.equals(request.protocolVersion())) {
            throw new AgentContractException(
                    "Unknown inference protocol version: " + request.protocolVersion());
        }
        if (request.runId() == null) {
            throw new AgentContractException("runId is required");
        }
        if (!AgentProtocol.CALL_TYPES.contains(request.callType())) {
            throw new AgentContractException("Unknown call type: " + request.callType());
        }
        List<ModelInferenceHttpRequest.Message> messages = request.messages();
        if (messages.isEmpty()) {
            throw new AgentContractException("messages are required");
        }
        int totalChars = 0;
        for (ModelInferenceHttpRequest.Message message : messages) {
            if (!ALLOWED_ROLES.contains(message.role())) {
                throw new AgentContractException("Unsupported message role: " + message.role());
            }
            if (message.content() == null) {
                throw new AgentContractException("message content is required");
            }
            totalChars += message.content().length();
        }
        if (totalChars > properties.getBroker().getMaxPromptChars()) {
            throw new AgentContractException(
                    "Prompt exceeds the broker size limit: " + totalChars);
        }
        Integer maxOutputTokens = request.maxOutputTokens();
        if (maxOutputTokens != null
                && (maxOutputTokens < 1 || maxOutputTokens > properties.getBroker().getMaxOutputTokens())) {
            throw new AgentContractException("maxOutputTokens outside the allowed range");
        }
    }

    private String concatMessages(ModelInferenceHttpRequest request) {
        StringBuilder combined = new StringBuilder();
        for (ModelInferenceHttpRequest.Message message : request.messages()) {
            combined.append(message.role()).append('\n').append(message.content()).append('\n');
        }
        return combined.toString();
    }

    private String sha256(String value) {
        return com.specagent.common.Hashes.sha256Hex(value);
    }
}
