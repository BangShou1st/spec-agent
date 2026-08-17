package com.specagent.model.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDK {@link HttpClient} implementation of the OpenCode Zen transport.
 *
 * <p>Every request carries the single transport-owned policy: User-Agent
 * {@code opencode/1.18.16}, bearer authorization when a key is available and
 * JSON content type for payload-bearing requests. Credential probes and model
 * list requests go through the exact same header building, so the policy can
 * never drift between request kinds.
 *
 * <p>All requests have bounded connect and request timeouts. Every failure is
 * mapped to a diagnosable {@link OpenCodeModelException}; messages never
 * contain the API key.
 */
@Component
public class HttpOpenCodeZenTransport implements OpenCodeZenTransport {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Model used by the bounded credential probe. The probe is not model
     * selection: it only proves the key authorizes a minimal completion through
     * the same transport policy the runtime will use.
     */
    private static final String PROBE_MODEL = "mimo-v2.5-free";
    private static final String PROBE_USER_CONTENT = "Return only {\"action\":\"finish\"}.";
    private static final int PROBE_MAX_TOKENS = 256;

    private final ObjectMapper mapper;
    private final String baseUrl;
    private final Duration requestTimeout;
    private final HttpClient httpClient;

    public HttpOpenCodeZenTransport(ObjectMapper mapper,
                                    @Value("${spec.agent.model.opencode.base-url:" + BASE_URL + "}") String baseUrl,
                                    @Value("${spec.agent.model.opencode.timeout-seconds:45}") long timeoutSeconds) {
        this.mapper = mapper;
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? BASE_URL : stripTrailingSlash(baseUrl);
        this.requestTimeout = Duration.ofSeconds(timeoutSeconds);
        this.httpClient = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
    }

    @Override
    public OpenCodeCompletionResponse complete(String apiKey, OpenCodeChatCompletionRequest request) {
        HttpResponse<String> response = send("POST", "/chat/completions", apiKey, completionPayload(request));
        return parseCompletion(response.body());
    }

    @Override
    public OpenCodeModelList listModels(String apiKey) {
        HttpResponse<String> response = send("GET", "/models", apiKey, null);
        return parseModelList(response.body());
    }

    @Override
    public void validateCredential(String apiKey) {
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("model", PROBE_MODEL);
        probe.put("messages", List.of(
                Map.of("role", "user", "content", PROBE_USER_CONTENT)));
        probe.put("max_tokens", PROBE_MAX_TOKENS);
        probe.put("response_format", Map.of("type", "json_object"));
        probe.put("stream", false);
        HttpResponse<String> response = send("POST", "/chat/completions", apiKey, writeJson(probe));
        parseCompletion(response.body());
    }

    /**
     * Sends one request with the transport-owned header policy and maps HTTP,
     * timeout and connection failures into {@link OpenCodeModelException}.
     */
    private HttpResponse<String> send(String method, String path, String apiKey, byte[] body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(requestTimeout)
                .header("User-Agent", USER_AGENT);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        HttpResponse<String> response;
        try {
            response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException ex) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.TIMEOUT,
                    "OpenCode request timed out", ex);
        } catch (IOException ex) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.CONNECTION,
                    "OpenCode connection failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.CONNECTION,
                    "OpenCode request was interrupted", ex);
        }

        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.AUTHENTICATION,
                    "OpenCode request failed (HTTP " + status + ")", status);
        }
        if (status == 429) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.RATE_LIMITED,
                    "OpenCode service rate limited the request", status);
        }
        if (status >= 500) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.SERVER_ERROR,
                    "OpenCode service is temporarily unavailable", status);
        }
        if (status >= 400) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.PROVIDER_REQUEST_ERROR,
                    "OpenCode request failed (HTTP " + status + ")", status);
        }
        return response;
    }

    private byte[] completionPayload(OpenCodeChatCompletionRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", request.model());
        payload.put("messages", request.messages());
        payload.put("temperature", request.temperature());
        payload.put("max_tokens", request.maxTokens());
        payload.put("response_format", Map.of("type", "json_object"));
        payload.put("stream", false);
        return writeJson(payload);
    }

    private OpenCodeCompletionResponse parseCompletion(String body) {
        JsonNode root = readJson(body);
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_RESPONSE,
                    "OpenCode returned an unexpected completion payload");
        }
        JsonNode message = choices.get(0).get("message");
        if (message == null) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_RESPONSE,
                    "OpenCode returned an unexpected completion payload");
        }
        JsonNode content = message.get("content");
        if (content == null || !content.isTextual() || content.asText().isBlank()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.EMPTY_CONTENT,
                    "OpenCode returned empty model content");
        }
        JsonNode usage = root.get("usage");
        return new OpenCodeCompletionResponse(
                content.asText(),
                textOrNull(choices.get(0).get("finish_reason")),
                intOrNull(usage == null ? null : usage.get("prompt_tokens")),
                intOrNull(usage == null ? null : usage.get("completion_tokens")),
                intOrNull(usage == null ? null : usage.get("total_tokens")));
    }

    private OpenCodeModelList parseModelList(String body) {
        JsonNode root = readJson(body);
        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_RESPONSE,
                    "OpenCode returned an unexpected model list payload");
        }
        List<OpenCodeModel> models = new ArrayList<>();
        for (JsonNode entry : data) {
            JsonNode id = entry.get("id");
            if (id == null || !id.isTextual() || id.asText().isBlank()) {
                continue;
            }
            models.add(new OpenCodeModel(id.asText(), textOrNull(entry.get("owned_by"))));
        }
        return new OpenCodeModelList(models);
    }

    private JsonNode readJson(String body) {
        try {
            return mapper.readTree(body);
        } catch (IOException ex) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_RESPONSE,
                    "OpenCode returned an invalid payload", ex);
        }
    }

    private byte[] writeJson(Object value) {
        try {
            return mapper.writeValueAsBytes(value);
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to serialize OpenCode request", ex);
        }
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String textOrNull(JsonNode node) {
        return node == null || !node.isTextual() ? null : node.asText();
    }

    private static Integer intOrNull(JsonNode node) {
        return node == null || !node.isIntegralNumber() ? null : node.asInt();
    }
}