package com.specagent.model.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JDK {@link HttpClient} implementation of the OpenCode Zen transport.
 *
 * <p>Production completions follow the OpenAI-compatible streaming shape used
 * by the verified OpenCode client: {@code stream=true}, no provider JSON-mode
 * field, and SSE delta aggregation. The transport converts that wire shape
 * back to the existing {@link OpenCodeCompletionResponse} contract, so the
 * runtime and structured-output validation remain provider-agnostic.</p>
 *
 * <p>Every request carries the single transport-owned policy: User-Agent
 * {@code opencode/1.18.16}, bearer authorization when a key is available and
 * JSON content type for payload-bearing requests. Credential probes stay a
 * separate bounded non-streaming request and do not change completion shape.</p>
 */
@Component
public class HttpOpenCodeZenTransport implements OpenCodeZenTransport {

    private static final Logger LOG = LoggerFactory.getLogger(HttpOpenCodeZenTransport.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_DIAGNOSTIC_BODY_BYTES = 16 * 1024;

    /** Bounded probe payload; probe wire shape is intentionally independent. */
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
        HttpResponse<InputStream> response = sendStreaming(
                "POST", "/chat/completions", apiKey, completionPayload(request), request.model());
        return parseStreaming(response.body());
    }

    @Override
    public OpenCodeModelList listModels(String apiKey) {
        HttpResponse<String> response = sendBuffered("GET", "/models", apiKey, null, null);
        return parseModelList(response.body());
    }

    @Override
    public void validateCredential(String apiKey, String model) {
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("model", model);
        probe.put("messages", List.of(Map.of("role", "user", "content", PROBE_USER_CONTENT)));
        probe.put("max_tokens", PROBE_MAX_TOKENS);
        probe.put("response_format", Map.of("type", "json_object"));
        probe.put("stream", false);
        HttpResponse<String> response = sendBuffered(
                "POST", "/chat/completions", apiKey, writeJson(probe), model);
        parseCompletion(response.body());
    }

    private HttpResponse<String> sendBuffered(String method,
                                              String path,
                                              String apiKey,
                                              byte[] body,
                                              String selectedModel) {
        HttpResponse<String> response = send(
                buildRequest(method, path, apiKey, body), HttpResponse.BodyHandlers.ofString());
        ensureSuccessful(response.statusCode(), path, selectedModel, response.body(), response.headers());
        return response;
    }

    private HttpResponse<InputStream> sendStreaming(String method,
                                                    String path,
                                                    String apiKey,
                                                    byte[] body,
                                                    String selectedModel) {
        HttpResponse<InputStream> response = send(
                buildRequest(method, path, apiKey, body), HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() >= 400) {
            String diagnosticBody = readBounded(response.body());
            ensureSuccessful(response.statusCode(), path, selectedModel, diagnosticBody, response.headers());
        }
        return response;
    }

    private HttpRequest buildRequest(String method, String path, String apiKey, byte[] body) {
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
        return builder.build();
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> bodyHandler) {
        try {
            return httpClient.send(request, bodyHandler);
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
    }

    private void ensureSuccessful(int status,
                                  String path,
                                  String selectedModel,
                                  String responseBody,
                                  HttpHeaders headers) {
        if (status == 401 || status == 403) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.AUTHENTICATION,
                    "OpenCode request failed (HTTP " + status + ")", status);
        }
        if (status == 429) {
            logProviderDiagnostics("rate-limit", path, selectedModel, responseBody, headers);
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.RATE_LIMITED,
                    "OpenCode service rate limited the request", status);
        }
        if (status >= 500) {
            logProviderDiagnostics("server-error", path, selectedModel, responseBody, headers);
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.SERVER_ERROR,
                    "OpenCode service is temporarily unavailable", status);
        }
        if (status >= 400) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.PROVIDER_REQUEST_ERROR,
                    "OpenCode request failed (HTTP " + status + ")", status);
        }
    }

    /**
     * Logs only allowlisted, bounded provider diagnostics for the hard-stop
     * rate-limit case. The response body is parsed in memory but never logged
     * wholesale; Authorization, API keys, prompts, and arbitrary headers are
     * never included.
     */
    private void logProviderDiagnostics(String category,
                                        String path,
                                        String selectedModel,
                                        String responseBody,
                                        HttpHeaders headers) {
        JsonNode error = null;
        try {
            JsonNode root = mapper.readTree(responseBody == null ? "" : responseBody);
            error = root == null ? null : root.get("error");
        } catch (IOException ignored) {
            // Keep the stable category even when the provider body is not JSON.
        }
        LOG.warn("OpenCode provider diagnostics category={} endpoint={} path={} providerType={} "
                        + "providerCode={} providerMessage={} retryAfter={} xRequestId={} "
                        + "requestId={} cfRay={} traceId={} selectedModel={}",
                category,
                baseUrl,
                path,
                safeDiagnostic(error == null ? null : error.get("type")),
                safeDiagnostic(error == null ? null : error.get("code")),
                safeDiagnostic(error == null ? null : error.get("message")),
                safeHeader(headers.firstValue("retry-after").orElse("not provided")),
                safeHeader(headers.firstValue("x-request-id").orElse("not provided")),
                safeHeader(headers.firstValue("request-id").orElse("not provided")),
                safeHeader(headers.firstValue("cf-ray").orElse("not provided")),
                safeHeader(headers.firstValue("trace-id").orElse("not provided")),
                safeHeader(selectedModel == null ? "not provided" : selectedModel));
    }

    private static String safeDiagnostic(JsonNode value) {
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return "not provided";
        }
        return safeHeader(value.asText());
    }

    private static String safeHeader(String value) {
        String singleLine = value.replaceAll("[\\r\\n\\t]", " ").trim();
        singleLine = singleLine.replaceAll("(?i)\\bBearer\\s+\\S+", "Bearer <redacted>")
                .replaceAll("(?i)\\bsk-[A-Za-z0-9._-]+", "sk-<redacted>");
        if (singleLine.length() <= 512) {
            return singleLine;
        }
        return singleLine.substring(0, 512);
    }

    private OpenCodeCompletionResponse parseStreaming(InputStream body) {
        StringBuilder eventData = new StringBuilder();
        StringBuilder content = new StringBuilder();
        String finishReason = null;
        Integer promptTokens = null;
        Integer completionTokens = null;
        Integer totalTokens = null;
        boolean done = false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    StreamChunk chunk = parseStreamEvent(eventData);
                    eventData.setLength(0);
                    if (chunk.done()) {
                        done = true;
                        break;
                    }
                    content.append(chunk.content());
                    finishReason = firstNonNull(chunk.finishReason(), finishReason);
                    promptTokens = firstNonNull(chunk.promptTokens(), promptTokens);
                    completionTokens = firstNonNull(chunk.completionTokens(), completionTokens);
                    totalTokens = firstNonNull(chunk.totalTokens(), totalTokens);
                } else if (line.startsWith("data:")) {
                    String data = line.substring("data:".length());
                    if (data.startsWith(" ")) {
                        data = data.substring(1);
                    }
                    if (eventData.length() > 0) {
                        eventData.append('\n');
                    }
                    eventData.append(data);
                }
                // SSE comments, event names and ids do not affect content.
            }

            if (!done && eventData.length() > 0) {
                StreamChunk chunk = parseStreamEvent(eventData);
                if (chunk.done()) {
                    done = true;
                } else {
                    content.append(chunk.content());
                    finishReason = firstNonNull(chunk.finishReason(), finishReason);
                    promptTokens = firstNonNull(chunk.promptTokens(), promptTokens);
                    completionTokens = firstNonNull(chunk.completionTokens(), completionTokens);
                    totalTokens = firstNonNull(chunk.totalTokens(), totalTokens);
                }
            }
        } catch (OpenCodeModelException ex) {
            throw ex;
        } catch (IOException ex) {
            if (ex instanceof HttpTimeoutException || ex instanceof SocketTimeoutException) {
                throw new OpenCodeModelException(OpenCodeModelErrorCategory.TIMEOUT,
                        "OpenCode streaming request timed out", ex);
            }
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.CONNECTION,
                    "OpenCode streaming response was interrupted", ex);
        }

        if (content.toString().isBlank()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.EMPTY_CONTENT,
                    "OpenCode returned empty streamed model content");
        }
        return new OpenCodeCompletionResponse(
                content.toString(), finishReason, promptTokens, completionTokens, totalTokens);
    }

    private StreamChunk parseStreamEvent(CharSequence eventData) {
        String data = eventData.toString().trim();
        if (data.isEmpty()) {
            return StreamChunk.empty();
        }
        if ("[DONE]".equals(data)) {
            return StreamChunk.doneChunk();
        }

        JsonNode root = readJson(data);
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_RESPONSE,
                    "OpenCode returned a malformed streaming chunk");
        }
        if (choices.isEmpty()) {
            return StreamChunk.empty();
        }
        JsonNode choice = choices.get(0);
        JsonNode delta = choice.get("delta");
        if (delta == null || !delta.isObject()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_RESPONSE,
                    "OpenCode returned a streaming chunk without delta content");
        }
        JsonNode deltaContent = delta.get("content");
        if (deltaContent != null && !deltaContent.isNull() && !deltaContent.isTextual()) {
            throw new OpenCodeModelException(OpenCodeModelErrorCategory.INVALID_RESPONSE,
                    "OpenCode returned non-text streaming content");
        }
        JsonNode usage = root.get("usage");
        return new StreamChunk(
                deltaContent == null || deltaContent.isNull() ? "" : deltaContent.asText(),
                textOrNull(choice.get("finish_reason")),
                false,
                intOrNull(usage == null ? null : usage.get("prompt_tokens")),
                intOrNull(usage == null ? null : usage.get("completion_tokens")),
                intOrNull(usage == null ? null : usage.get("total_tokens")));
    }

    private byte[] completionPayload(OpenCodeChatCompletionRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", request.model());
        payload.put("messages", request.messages());
        payload.put("temperature", request.temperature());
        payload.put("max_tokens", request.maxTokens());
        payload.put("stream", true);
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

    private static String readBounded(InputStream body) {
        if (body == null) {
            return "";
        }
        try (InputStream input = body; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int remaining = MAX_DIAGNOSTIC_BODY_BYTES;
            int read;
            while (remaining > 0 && (read = input.read(buffer, 0, Math.min(buffer.length, remaining))) >= 0) {
                if (read > 0) {
                    output.write(buffer, 0, read);
                    remaining -= read;
                }
            }
            return output.toString(StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
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

    private static <T> T firstNonNull(T candidate, T fallback) {
        return candidate == null ? fallback : candidate;
    }

    private record StreamChunk(
            String content,
            String finishReason,
            boolean done,
            Integer promptTokens,
            Integer completionTokens,
            Integer totalTokens) {

        private StreamChunk {
            content = content == null ? "" : content;
        }

        private static StreamChunk empty() {
            return new StreamChunk("", null, false, null, null, null);
        }

        private static StreamChunk doneChunk() {
            return new StreamChunk("", null, true, null, null, null);
        }
    }
}
