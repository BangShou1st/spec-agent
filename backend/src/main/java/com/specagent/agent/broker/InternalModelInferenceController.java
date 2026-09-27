package com.specagent.agent.broker;

import com.specagent.agent.broker.AgentBrainProperties;
import com.specagent.agent.protocol.AgentContracts;
import com.specagent.agent.protocol.AgentProtocol;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import com.specagent.agent.protocol.AgentContractException;
import com.specagent.model.contract.ModelGatewayException;
import com.specagent.model.contract.ModelInferenceGateway;
import com.specagent.model.contract.ModelInferenceMessage;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelOutputContract;
import com.specagent.model.contract.ModelInferenceResponse;
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
 * 文件名:InternalModelInferenceController.java
 *
 * 用途:面向 Python agent Brain 的内部、需认证的模型推理 broker。
 * 这不是产品 API。Brain 调用它,使 provider 传输层绝不在 Python 中重复:
 * 凭据、模型选择和冻结的 OpenCode 传输都留在 Java 侧,Python 永远
 * 拿不到任何密钥。
 *
 * 此处强制的安全要求:共享内部密钥(恒定时间比较)、请求必须绑定
 * {@code runId} 且调用类型属于封闭集合、提示词大小有界、不转发任意
 * URL/header、无 provider 回退、无隐藏重试,AgentRun 事件只记录
 * 脱敏信息(仅调用类型 + 哈希)。
 *
 * 协作:Python Brain 在执行推理时调用本端点;Java 侧经
 * ModelInferenceGateway 转发到 provider。
 */
@RestController
@RequestMapping("/internal/v1/model-inference")
public class InternalModelInferenceController {

    private static final Logger LOG = LoggerFactory.getLogger(InternalModelInferenceController.class);

    private static final Set<String> ALLOWED_ROLES = Set.of("system", "user");

    private final ModelInferenceGateway gateway;
    private final AgentRunEventService eventService;
    private final AgentBrainProperties properties;
    private final RunExistenceCheck runExistenceCheck;
    private final RunProjectLookup runProjectLookup;

    public InternalModelInferenceController(ModelInferenceGateway gateway,
                                            AgentRunEventService eventService,
                                            AgentBrainProperties properties,
                                            RunExistenceCheck runExistenceCheck,
                                            RunProjectLookup runProjectLookup) {
        this.gateway = gateway;
        this.eventService = eventService;
        this.properties = properties;
        this.runExistenceCheck = runExistenceCheck;
        this.runProjectLookup = runProjectLookup;
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
            // 一个项目即一个 provider 侧会话:run 所属项目成为会话身份,
            // 因此项目内的每次模型调用共享一个会话,而请求 id 仍按次区分。
            UUID conversationId = resolveConversationId(request.runId());
            response = gateway.complete(new ModelInferenceRequest(
                    request.runId(),
                    request.callType(),
                    request.messages().stream()
                            .map(message -> new ModelInferenceMessage(message.role(), message.content()))
                            .toList(),
                    request.maxOutputTokens(),
                    ModelOutputContract.jsonObject(), // brain 严格按 JSON 解析;强制结构化输出
                    conversationId));
        } catch (ModelGatewayException ex) {
            recordFailure(request, ex);
            // 只返回 provider 中立的错误类别;provider 载荷绝不离开此处。
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

    /**
     * 项目亲和只是关联细节,绝不是模型调用失败的理由:项目无法解析时,
     * 会话简单地回退为按 run 区分。
     */
    private UUID resolveConversationId(UUID runId) {
        try {
            return runProjectLookup.projectIdOf(runId);
        } catch (RuntimeException ex) {
            LOG.warn("Owning project lookup failed for run {}; continuing without project affinity: {}",
                    runId, ex.getMessage());
            return null;
        }
    }

    private void recordFailure(ModelInferenceHttpRequest request, ModelGatewayException ex) {        Map<String, Object> payload = new LinkedHashMap<>();
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
        if (!runExistenceCheck.exists(request.runId())) {
            throw new AgentContractException("runId does not correspond to a persisted run");
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
