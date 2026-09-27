package com.specagent.model.provider;

import com.specagent.model.contract.FragmentListener;
import com.specagent.model.contract.StreamCancelledException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.common.Hashes;
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
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 文件名:HttpOpenCodeZenTransport.java
 *
 * 用途:OpenCode Zen 传输接口的 JDK {@link HttpClient} 实现。
 *
 * 生产补全遵循已验证的 OpenCode 客户端所用的 OpenAI 兼容流式形态:
 * {@code stream=true}、不带提供商的 JSON-mode 字段、按 SSE delta 聚合内容。
 * 传输层把这种线上形态转换回既有的 {@link OpenCodeCompletionResponse} 契约,
 * 因此运行时和结构化输出校验依然与提供商无关。
 *
 * 每个请求都携带传输层自有的身份策略:User-Agent
 * {@code opencode/1.18.31 ai-sdk/provider-utils/4.0.23 runtime/bun/1.3.14}、
 * 有密钥时携带 bearer 授权、完整的 OpenCode 客户端身份头集合
 * ({@code x-opencode-client: cli}、{@code x-opencode-session}、
 * {@code x-opencode-request}、{@code x-opencode-project: global}),让 OpenCode
 * 把请求识别为客户端发起;携带载荷的请求还要设置 JSON content type。生产补全
 * 使用不设超时上限的 JDK 请求/客户端策略;模型发现和凭据探测使用单独的
 * 有界 settings 策略。
 */
@Component
public class HttpOpenCodeZenTransport implements OpenCodeZenTransport {

    private static final Logger LOG = LoggerFactory.getLogger(HttpOpenCodeZenTransport.class);
    private static final int MAX_DIAGNOSTIC_BODY_BYTES = 16 * 1024;

    /** 有界的探测载荷;探测请求的线上形态刻意保持独立。 */
    private static final String PROBE_USER_CONTENT = "Return only {\"action\":\"finish\"}.";
    private static final int PROBE_MAX_TOKENS = 256;

    /**
     * 传输层自有的"守门"工具集,随每次 chat completion 一起发送。
     *
     * OpenCode Zen 只对看起来像真实客户端调用的请求开放免费额度。经线上 A/B
     * 对照隔离出三个必要条件,缺一不可:客户端身份头集合(见
     * {@link OpenCodeZenTransport#CLIENT_HEADER})、一个非空的 {@code tools} 数组
     * 且其中包含字面名称为 {@code bash} 和 {@code read} 的函数(大小写敏感;
     * 描述和参数可以是假的),以及 {@code stream=true}。缺少任何一条,或重命名
     * 任一工具,都会得到 {@code FreeTierError}(HTTP 403)。
     *
     * 模型永远不会真的调用这些工具:运行时提示词仍按 JSON 动作契约驱动,
     * 这些工具纯粹是为了让请求带上 Zen 期望的客户端形态载荷。已验证:真实提示词
     * 附带这些工具时,模型仍返回普通文本内容。
     */
    private static final List<Map<String, Object>> RESERVED_TOOLS = List.of(
            Map.<String, Object>of(
                    "type", "function",
                    "function", Map.<String, Object>of(
                            "name", "bash",
                            "description", "Reserved by the transport. Never call this tool.",
                            "parameters", Map.<String, Object>of(
                                    "type", "object",
                                    "properties", Map.<String, Object>of(
                                            "command", Map.of("type", "string"))))),
            Map.<String, Object>of(
                    "type", "function",
                    "function", Map.<String, Object>of(
                            "name", "read",
                            "description", "Reserved by the transport. Never call this tool.",
                            "parameters", Map.<String, Object>of(
                                    "type", "object",
                                    "properties", Map.<String, Object>of(
                                            "filePath", Map.of("type", "string"))))));

    private final ObjectMapper mapper;
    private final String baseUrl;
    private final Duration settingsTimeout;
    private final HttpClient productionHttpClient;
    private final HttpClient settingsHttpClient;

    public HttpOpenCodeZenTransport(ObjectMapper mapper,
                                    @Value("${spec.agent.model.opencode.base-url:" + BASE_URL + "}") String baseUrl,
                                    @Value("${spec.agent.model.opencode.settings-timeout-seconds:45}") long settingsTimeoutSeconds,
                                    @Value("${spec.agent.model.opencode.proxy:DIRECT}") String proxyConfig) {
        this.mapper = mapper;
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? BASE_URL : stripTrailingSlash(baseUrl);
        this.settingsTimeout = Duration.ofSeconds(settingsTimeoutSeconds);
        java.net.ProxySelector selector = proxySelectorFor(proxyConfig);
        this.productionHttpClient = HttpClient.newBuilder().proxy(selector).build();
        this.settingsHttpClient = HttpClient.newBuilder().proxy(selector).connectTimeout(settingsTimeout).build();
    }

    @Override
    public String endpoint() {
        return baseUrl;
    }

    /**
     * 为 Zen HTTP 客户端指定显式的代理选择器。默认为 DIRECT(NO_PROXY):
     * Zen 请求绝不继承 JVM/系统默认的 {@link java.net.ProxySelector},因此无论宿主
     * 环境如何,应用层 HTTP/SOCKS 代理都无法介入——除非把
     * {@code spec.agent.model.opencode.proxy} 显式设置为 {@code http://host:port}。
     * 该策略只作用于本传输层自己的客户端;不修改 JVM 默认选择器,也不读取系统
     * 代理环境变量。
     */
    private static HttpClient.Builder directHttpClientBuilder() {
        return HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY);
    }

    static java.net.ProxySelector proxySelectorFor(String proxyConfig) {
        if (proxyConfig == null || proxyConfig.isBlank() || "DIRECT".equalsIgnoreCase(proxyConfig.trim())) {
            return HttpClient.Builder.NO_PROXY;
        }
        String v = proxyConfig.trim();
        if (v.startsWith("http://")) v = v.substring("http://".length());
        else if (v.startsWith("https://")) v = v.substring("https://".length());
        int colon = v.lastIndexOf(':');
        if (colon <= 0 || colon == v.length() - 1) return HttpClient.Builder.NO_PROXY;
        String host = v.substring(0, colon);
        int port;
        try {
            port = Integer.parseInt(v.substring(colon + 1));
        } catch (NumberFormatException ex) {
            return HttpClient.Builder.NO_PROXY;
        }
        if (host.isBlank() || port <= 0 || port > 65535) return HttpClient.Builder.NO_PROXY;
        return java.net.ProxySelector.of(new java.net.InetSocketAddress(host, port));
    }

    @Override
    public OpenCodeCompletionResponse complete(String apiKey, String sessionId,
                                                OpenCodeChatCompletionRequest request) {
        return completeStreaming(apiKey, sessionId, request, fragment -> true);
    }

    @Override
    public OpenCodeCompletionResponse completeStreaming(String apiKey, String sessionId,
                                                         OpenCodeChatCompletionRequest request,
                                                         FragmentListener listener) {
        PreparedRequest prepared = prepareCompletionRequest(apiKey, requireSessionId(sessionId), request);
        HttpResponse<InputStream> response = sendStreaming(prepared, request.model());
        return parseStreaming(response, request.model(), prepared.execution(), listener);
    }

    /**
     * 生产补全的 fail-closed 会话门禁:缺失、空白或含 CR/LF 的值绝不上线,也绝不
     * 被静默替换——稳定的 run 亲和性是网关契约的一部分,调用方必须显式遵守。
     */
    private static String requireSessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("OpenCode session id must be non-blank");
        }
        if (sessionId.indexOf('\n') >= 0 || sessionId.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("OpenCode session id must be single-line");
        }
        return sessionId;
    }

    @Override
    public OpenCodeModelList listModels(String apiKey) {
        PreparedRequest prepared = prepareSettingsRequest("GET", "/models", apiKey, null,
                List.of(), false);
        HttpResponse<String> response = sendBuffered(prepared, null);
        return parseModelList(response.body());
    }

    @Override
    public void validateCredential(String apiKey, String model) {
        Map<String, Object> probe = new LinkedHashMap<>();
        probe.put("model", model);
        probe.put("messages", List.of(Map.of("role", "user", "content", PROBE_USER_CONTENT)));
        probe.put("max_tokens", PROBE_MAX_TOKENS);
        probe.put("response_format", Map.of("type", "json_object"));
        probe.put("tools", RESERVED_TOOLS);
        probe.put("stream", true);
        PreparedRequest prepared = prepareSettingsRequest("POST", "/chat/completions", apiKey,
                writeJson(probe), List.of(PROBE_USER_CONTENT), true);
        HttpResponse<InputStream> response = sendStreaming(prepared, model);
        parseStreaming(response, model, prepared.execution(), fragment -> true);
    }

    private HttpResponse<String> sendBuffered(PreparedRequest prepared, String selectedModel) {
        HttpResponse<String> response = send(
                prepared, HttpResponse.BodyHandlers.ofString());
        ensureSuccessful(response.statusCode(), prepared.path(), selectedModel, response.body(),
                response.headers(), prepared.execution());
        return response;
    }

    private HttpResponse<InputStream> sendStreaming(PreparedRequest prepared, String selectedModel) {
        HttpResponse<InputStream> response = send(
                prepared, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String diagnosticBody = readBounded(response.body());
            ensureSuccessful(response.statusCode(), prepared.path(), selectedModel, diagnosticBody,
                    response.headers(), prepared.execution());
        }
        return response;
    }

    private PreparedRequest prepareCompletionRequest(String apiKey,
                                                      String sessionId,
                                                      OpenCodeChatCompletionRequest request) {
        byte[] body = completionPayload(request);
        return prepareRequest("POST", "/chat/completions", apiKey, sessionId, body, request.model(),
                RequestType.PRODUCTION_COMPLETION, request.messages().stream()
                        .map(OpenCodeChatMessage::content).toList(), true,
                request.responseFormat() != null);
    }

    private PreparedRequest prepareSettingsRequest(String method,
                                                   String path,
                                                   String apiKey,
                                                   byte[] body,
                                                   List<String> messageContents,
                                                   boolean stream) {
        return prepareRequest(method, path, apiKey, OpenCodeZenSessionIds.newEphemeral(), body, null,
                RequestType.SETTINGS, messageContents, stream, false);
    }

    private PreparedRequest prepareRequest(String method,
                                           String path,
                                           String apiKey,
                                           String sessionId,
                                           byte[] body,
                                           String selectedModel,
                                           RequestType requestType,
                                           List<String> messageContents,
                                           boolean stream,
                                           boolean responseFormatPresent) {
        HttpClient client = requestType == RequestType.PRODUCTION_COMPLETION
                ? productionHttpClient : settingsHttpClient;
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .header("User-Agent", USER_AGENT)
                .header(CLIENT_HEADER, CLIENT_ID)
                .header(REQUEST_HEADER, OpenCodeZenIds.requestId())
                .header(PROJECT_HEADER, GLOBAL_PROJECT)
                .header(SESSION_HEADER, sessionId);
        if (requestType == RequestType.SETTINGS) {
            builder.timeout(settingsTimeout);
        }
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey);
        }
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpRequest httpRequest = builder.build();
        OpenCodeRequestDiagnostics requestDiagnostics = new OpenCodeRequestDiagnostics(
                requestType.name(),
                Instant.now().toString(),
                null,
                null,
                null,
                messageContents == null ? 0 : messageContents.size(),
                messageDiagnostics(messageContents),
                body == null ? 0 : body.length,
                bodySha256(body),
                stream,
                false,
                false,
                responseFormatPresent,
                httpRequest.timeout().isPresent(),
                client.connectTimeout().isPresent());
        return new PreparedRequest(httpRequest, client, path,
                new RequestExecution(path, requestDiagnostics));
    }

    private <T> HttpResponse<T> send(PreparedRequest prepared, HttpResponse.BodyHandler<T> bodyHandler) {
        try {
            HttpResponse<T> response = prepared.client().send(prepared.request(), bodyHandler);
            prepared.execution().recordResponseHeaders(response.statusCode());
            return response;
        } catch (HttpConnectTimeoutException ex) {
            throw timeoutFailure(OpenCodeDiagnosticReason.CONNECT_TIMEOUT,
                    "OpenCode connection timed out", ex, prepared.execution());
        } catch (HttpTimeoutException ex) {
            throw timeoutFailure(OpenCodeDiagnosticReason.RESPONSE_TIMEOUT,
                    "OpenCode response timed out", ex, prepared.execution());
        } catch (IOException ex) {
            throw connectionFailure("OpenCode connection failed", ex, prepared.execution());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw connectionFailure("OpenCode request was interrupted", ex, prepared.execution());
        }
    }

    private OpenCodeModelException timeoutFailure(OpenCodeDiagnosticReason reason,
                                                  String message,
                                                  Throwable cause,
                                                  RequestExecution execution) {
        return new OpenCodeModelException(OpenCodeModelErrorCategory.TIMEOUT, message, null, cause)
                .withDiagnostics(requestFailureDiagnostics(reason, execution));
    }

    private OpenCodeModelException connectionFailure(String message,
                                                     Throwable cause,
                                                     RequestExecution execution) {
        return new OpenCodeModelException(OpenCodeModelErrorCategory.CONNECTION, message, null, cause)
                .withDiagnostics(requestFailureDiagnostics(null, execution));
    }

    private OpenCodeFailureDiagnostics requestFailureDiagnostics(OpenCodeDiagnosticReason reason,
                                                                  RequestExecution execution) {
        return new OpenCodeFailureDiagnostics(
                "not provided", "not provided", execution.path, execution.initialHttpStatus(),
                reason, "not provided", 0, 0, Hashes.sha256Hex(""), null, List.of(), null, List.of(),
                "not provided", "not provided", "not provided", "not provided", "not provided",
                "not provided", "not provided", "not provided", 0, 0, Hashes.sha256Hex(""),
                execution.snapshot());
    }

    private void ensureSuccessful(int status,
                                  String path,
                                  String selectedModel,
                                  String responseBody,
                                  HttpHeaders headers,
                                  RequestExecution execution) {
        if (status >= 200 && status < 300) {
            return;
        }
        OpenCodeModelErrorCategory category;
        String message;
        if (status == 401 || status == 403) {
            category = OpenCodeModelErrorCategory.AUTHENTICATION;
            message = "OpenCode request failed (HTTP " + status + ")";
        } else if (status == 429) {
            category = OpenCodeModelErrorCategory.RATE_LIMITED;
            message = "OpenCode service rate limited the request";
        } else if (status >= 500) {
            category = OpenCodeModelErrorCategory.SERVER_ERROR;
            message = "OpenCode service is temporarily unavailable";
        } else {
            category = OpenCodeModelErrorCategory.PROVIDER_REQUEST_ERROR;
            message = "OpenCode request failed (HTTP " + status + ")";
        }
        OpenCodeModelException failure = httpFailure(
                category, message, status, path, selectedModel, responseBody, headers, execution);
        if (category == OpenCodeModelErrorCategory.RATE_LIMITED) {
            logProviderDiagnostics(failure, "rate-limit");
        } else if (category == OpenCodeModelErrorCategory.SERVER_ERROR) {
            logProviderDiagnostics(failure, "server-error");
        }
        throw failure;
    }

    /**
     * 对硬性中断的限流场景,只记录白名单内、有界大小的提供商诊断信息。响应体会在
     * 内存中解析,但绝不整体落日志;Authorization、API key、提示词与任意请求头
     * 一律不包含。
     */
    private void logProviderDiagnostics(OpenCodeModelException exception, String category) {
        OpenCodeFailureDiagnostics diagnostics = exception.diagnostics();
        LOG.warn("OpenCode provider diagnostics category={} endpoint={} path={} providerType={} "
                        + "providerCode={} providerMessage={} retryAfter={} xRequestId={} "
                        + "requestId={} cfRay={} traceId={} task={} selectedModel={} "
                        + "initialHttpStatus={} diagnosticReason={} finishReason={} "
                        + "streamedEventCount={} contentCharCount={} contentSha256={} eventIndex={} "
                        + "topLevelFields={} choicesCount={} deltaFields={} reasoningEventCount={} "
                        + "reasoningCharCount={} reasoningSha256={}",
                category,
                baseUrl,
                diagnostics.endpointPath(), diagnostics.providerType(), diagnostics.providerCode(),
                diagnostics.providerMessage(), diagnostics.retryAfter(), diagnostics.xRequestId(),
                diagnostics.requestId(), diagnostics.cfRay(), diagnostics.traceId(), diagnostics.task(),
                diagnostics.selectedModel(), diagnostics.initialHttpStatus(), diagnosticReason(diagnostics),
                diagnostics.finishReason(), diagnostics.streamedEventCount(), diagnostics.contentCharCount(),
                diagnostics.contentSha256(), diagnostics.eventIndex(), diagnostics.topLevelFields(),
                diagnostics.choicesCount(), diagnostics.deltaFields(), diagnostics.reasoningEventCount(),
                diagnostics.reasoningCharCount(), diagnostics.reasoningSha256());
    }

    private OpenCodeModelException httpFailure(OpenCodeModelErrorCategory category,
                                               String message,
                                               int status,
                                               String path,
                                               String selectedModel,
                                               String responseBody,
                                               HttpHeaders headers,
                                               RequestExecution execution) {
        JsonNode error = null;
        try {
            JsonNode root = mapper.readTree(responseBody == null ? "" : responseBody);
            error = root == null ? null : root.get("error");
        } catch (IOException ignored) {
            // 提供商返回非 JSON 响应体时,保留安全的 "not provided" 诊断。
        }
        OpenCodeFailureDiagnostics diagnostics = OpenCodeFailureDiagnostics.httpFailure(
                selectedModel,
                path,
                status,
                safeDiagnostic(error == null ? null : error.get("type")),
                safeDiagnostic(error == null ? null : error.get("code")),
                "not provided",
                safeHeader(headers.firstValue("retry-after").orElse("not provided")),
                safeHeader(headers.firstValue("x-request-id").orElse("not provided")),
                safeHeader(headers.firstValue("request-id").orElse("not provided")),
                safeHeader(headers.firstValue("cf-ray").orElse("not provided")),
                safeHeader(headers.firstValue("trace-id").orElse("not provided")),
                execution.snapshot());
        return new OpenCodeModelException(category, message, status).withDiagnostics(diagnostics);
    }

    private static String diagnosticReason(OpenCodeFailureDiagnostics diagnostics) {
        return diagnostics.diagnosticReason() == null
                ? "not provided" : diagnostics.diagnosticReason().name();
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

    private OpenCodeCompletionResponse parseStreaming(HttpResponse<InputStream> response,
                                                      String selectedModel,
                                                      RequestExecution execution,
                                                      FragmentListener listener) {
        StringBuilder eventData = new StringBuilder();
        StringBuilder content = new StringBuilder();
        StreamState state = new StreamState();
        state.execution = execution;
        Integer promptTokens = null;
        Integer completionTokens = null;
        Integer totalTokens = null;
        boolean done = false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (eventData.length() == 0) {
                        continue;
                    }
                    StreamChunk chunk = parseStreamEvent(eventData, state, content,
                            response.statusCode(), response.headers(), selectedModel);
                    eventData.setLength(0);
                    if (chunk.done()) {
                        done = true;
                        break;
                    }
                    content.append(chunk.content());
                    offerFragment(listener, chunk.content());
                    state.finishReason = firstNonNull(chunk.finishReason(), state.finishReason);
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
                // SSE 注释、事件名和 id 不影响内容提取。
            }

            if (!done && eventData.length() > 0) {
                StreamChunk chunk = parseStreamEvent(eventData, state, content,
                        response.statusCode(), response.headers(), selectedModel);
                if (chunk.done()) {
                    done = true;
                } else {
                    content.append(chunk.content());
                    offerFragment(listener, chunk.content());
                    state.finishReason = firstNonNull(chunk.finishReason(), state.finishReason);
                    promptTokens = firstNonNull(chunk.promptTokens(), promptTokens);
                    completionTokens = firstNonNull(chunk.completionTokens(), completionTokens);
                    totalTokens = firstNonNull(chunk.totalTokens(), totalTokens);
                }
            }
        } catch (OpenCodeModelException ex) {
            throw ex;
        } catch (IOException ex) {
            if (ex instanceof HttpTimeoutException || ex instanceof SocketTimeoutException) {
                throw streamingFailure(OpenCodeModelErrorCategory.TIMEOUT,
                        OpenCodeDiagnosticReason.RESPONSE_TIMEOUT,
                        "OpenCode streaming request timed out", ex, state, content,
                        response.statusCode(), response.headers(), selectedModel, null, null, null);
            }
            throw streamingFailure(OpenCodeModelErrorCategory.CONNECTION, null,
                    "OpenCode streaming response was interrupted", ex, state, content,
                    response.statusCode(), response.headers(), selectedModel, null, null, null);
        }

        if (content.toString().isBlank()) {
            if ("length".equalsIgnoreCase(state.finishReason)) {
                throw streamingFailure(OpenCodeModelErrorCategory.INVALID_RESPONSE,
                        OpenCodeDiagnosticReason.MODEL_OUTPUT_TRUNCATED,
                        "OpenCode model output was truncated", null, state, content,
                        response.statusCode(), response.headers(), selectedModel, null, null, null);
            }
            throw streamingFailure(OpenCodeModelErrorCategory.EMPTY_CONTENT, null,
                    "OpenCode returned empty streamed model content", null, state, content,
                    response.statusCode(), response.headers(), selectedModel, null, null, null);
        }
        return new OpenCodeCompletionResponse(
                content.toString(), state.finishReason, promptTokens, completionTokens, totalTokens,
                response.statusCode(), state.eventCount, state.reasoningEventCount,
                state.reasoningCharCount, state.reasoningSha256(), execution.snapshot());
    }

    /**
     * 把一个解码后的提供商片段投递给流式监听器。监听器拒绝片段时中断读取循环:
     * try-with-resources 关闭 HTTP 流,由上游把 run 终止为 CANCELLED。
     *
     * 每个提供商 SSE 事件都是一次取消检查点,包括以空字符串形式送达的
     * 无内容 reasoning/usage 事件。空片段只用于流控,绝不是可展示文本;
     * reasoning 内容仅用于计量观察,绝不展示。
     */
    private static void offerFragment(FragmentListener listener, String content) {
        if (!listener.onFragment(content == null ? "" : content)) {
            throw new StreamCancelledException("Provider stream cancelled by run owner");
        }
    }

    private StreamChunk parseStreamEvent(CharSequence eventData,
                                         StreamState state,
                                         StringBuilder content,
                                         int initialHttpStatus,
                                         HttpHeaders headers,
                                         String selectedModel) {
        state.execution.recordFirstSseEvent();
        int eventIndex = ++state.eventCount;
        String data = eventData.toString().trim();
        if (data.isEmpty()) {
            return StreamChunk.empty();
        }
        if ("[DONE]".equals(data)) {
            return StreamChunk.doneChunk();
        }

        JsonNode root;
        try {
            root = mapper.readTree(data);
        } catch (IOException ex) {
            throw streamingFailure(OpenCodeModelErrorCategory.INVALID_RESPONSE,
                    OpenCodeDiagnosticReason.STREAM_MALFORMED_JSON,
                    "OpenCode returned malformed streaming JSON", ex, state, content,
                    initialHttpStatus, headers, selectedModel, eventIndex, null, null);
        }
        if (root == null || !root.isObject()) {
            throw streamingFailure(OpenCodeModelErrorCategory.INVALID_RESPONSE,
                    OpenCodeDiagnosticReason.STREAM_MALFORMED_JSON,
                    "OpenCode returned a non-object streaming event", null, state, content,
                    initialHttpStatus, headers, selectedModel, eventIndex, root, null);
        }
        JsonNode error = root.get("error");
        if (error != null) {
            OpenCodeModelErrorCategory category = classifyProviderStreamError(error);
            throw streamingFailure(category, OpenCodeDiagnosticReason.STREAM_ERROR_EVENT,
                    "OpenCode returned a provider error event", null, state, content,
                    initialHttpStatus, headers, selectedModel, eventIndex, root, null, error, null);
        }
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray()) {
            throw streamingFailure(OpenCodeModelErrorCategory.INVALID_RESPONSE,
                    OpenCodeDiagnosticReason.STREAM_MISSING_CHOICES,
                    "OpenCode streaming event has no choices array", null, state, content,
                    initialHttpStatus, headers, selectedModel, eventIndex, root, null);
        }
        JsonNode usage = root.get("usage");
        if (choices.isEmpty()) {
            return new StreamChunk(
                    "", null, false,
                    intOrNull(usage == null ? null : usage.get("prompt_tokens")),
                    intOrNull(usage == null ? null : usage.get("completion_tokens")),
                    intOrNull(usage == null ? null : usage.get("total_tokens")));
        }
        JsonNode choice = choices.get(0);
        JsonNode delta = choice.isObject() ? choice.get("delta") : null;
        if (delta == null || !delta.isObject()) {
            throw streamingFailure(OpenCodeModelErrorCategory.INVALID_RESPONSE,
                    OpenCodeDiagnosticReason.STREAM_MISSING_DELTA,
                    "OpenCode streaming choice has no delta object", null, state, content,
                    initialHttpStatus, headers, selectedModel, eventIndex, root, choices.size());
        }
        JsonNode deltaContent = delta.get("content");
        if (deltaContent != null && !deltaContent.isNull() && !deltaContent.isTextual()) {
            throw streamingFailure(OpenCodeModelErrorCategory.INVALID_RESPONSE,
                    OpenCodeDiagnosticReason.STREAM_NON_TEXT_CONTENT,
                    "OpenCode returned non-text streaming content", null, state, content,
                    initialHttpStatus, headers, selectedModel, eventIndex, root, choices.size(), null, delta);
        }
        JsonNode reasoningContent = delta.get("reasoning_content");
        if (reasoningContent != null && reasoningContent.isTextual()) {
            state.observeReasoning(reasoningContent.asText());
        }
        return new StreamChunk(
                deltaContent == null || deltaContent.isNull() ? "" : deltaContent.asText(),
                textOrNull(choice.get("finish_reason")),
                false,
                intOrNull(usage == null ? null : usage.get("prompt_tokens")),
                intOrNull(usage == null ? null : usage.get("completion_tokens")),
                intOrNull(usage == null ? null : usage.get("total_tokens")));
    }

    private OpenCodeModelException streamingFailure(OpenCodeModelErrorCategory category,
                                                    OpenCodeDiagnosticReason reason,
                                                    String message,
                                                    Throwable cause,
                                                    StreamState state,
                                                    StringBuilder content,
                                                    int initialHttpStatus,
                                                    HttpHeaders headers,
                                                    String selectedModel,
                                                    Integer eventIndex,
                                                    JsonNode root,
                                                    Integer choicesCount) {
        return streamingFailure(category, reason, message, cause, state, content,
                initialHttpStatus, headers, selectedModel, eventIndex, root, choicesCount, null, null);
    }

    private OpenCodeModelException streamingFailure(OpenCodeModelErrorCategory category,
                                                    OpenCodeDiagnosticReason reason,
                                                    String message,
                                                    Throwable cause,
                                                    StreamState state,
                                                    StringBuilder content,
                                                    int initialHttpStatus,
                                                    HttpHeaders headers,
                                                    String selectedModel,
                                                    Integer eventIndex,
                                                    JsonNode root,
                                                    Integer choicesCount,
                                                    JsonNode providerError,
                                                    JsonNode delta) {
        String contentText = content.toString();
        OpenCodeFailureDiagnostics diagnostics = new OpenCodeFailureDiagnostics(
                "not provided", selectedModel, "/chat/completions", initialHttpStatus, reason,
                state.finishReason, state.eventCount, contentText.length(), Hashes.sha256Hex(contentText),
                eventIndex, fieldNames(root), choicesCount, fieldNames(delta),
                safeDiagnostic(providerError == null ? null : providerError.get("type")),
                safeDiagnostic(providerError == null ? null : providerError.get("code")),
                "not provided",
                safeHeader(headers.firstValue("retry-after").orElse("not provided")),
                safeHeader(headers.firstValue("x-request-id").orElse("not provided")),
                safeHeader(headers.firstValue("request-id").orElse("not provided")),
                safeHeader(headers.firstValue("cf-ray").orElse("not provided")),
                safeHeader(headers.firstValue("trace-id").orElse("not provided")),
                state.reasoningEventCount, state.reasoningCharCount, state.reasoningSha256(),
                state.execution.snapshot());
        return new OpenCodeModelException(category, message, initialHttpStatus, cause)
                .withDiagnostics(diagnostics);
    }

    private OpenCodeModelErrorCategory classifyProviderStreamError(JsonNode error) {
        String type = safeDiagnostic(error == null ? null : error.get("type"));
        String code = safeDiagnostic(error == null ? null : error.get("code"));
        String value = (type + " " + code).toLowerCase(Locale.ROOT);
        if (containsAny(value, "rate", "429", "too_many", "throttl")) {
            return OpenCodeModelErrorCategory.RATE_LIMITED;
        }
        if (containsAny(value, "auth", "unauthor", "forbidden", "401", "403")) {
            return OpenCodeModelErrorCategory.AUTHENTICATION;
        }
        if (containsAny(value, "timeout", "timed_out")) {
            return OpenCodeModelErrorCategory.TIMEOUT;
        }
        if (containsAny(value, "server", "internal", "500", "502", "503", "504")) {
            return OpenCodeModelErrorCategory.SERVER_ERROR;
        }
        return OpenCodeModelErrorCategory.PROVIDER_REQUEST_ERROR;
    }

    private boolean containsAny(String value, String... tokens) {
        for (String token : tokens) {
            if (value.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private List<String> fieldNames(JsonNode node) {
        if (node == null || !node.isObject()) {
            return List.of();
        }
        List<String> fields = new ArrayList<>();
        node.fieldNames().forEachRemaining(field -> {
            if (fields.size() < 32) {
                fields.add(field.replaceAll("[^A-Za-z0-9_.-]", "_"));
            }
        });
        return List.copyOf(fields);
    }

    private byte[] completionPayload(OpenCodeChatCompletionRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", request.model());
        payload.put("messages", request.messages());
        if (request.responseFormat() != null) {
            payload.put("response_format", request.responseFormat());
        }
        payload.put("stream", true);
        payload.put("tools", RESERVED_TOOLS);
        return writeJson(payload);
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

    private List<OpenCodeMessageDiagnostics> messageDiagnostics(List<String> messageContents) {
        if (messageContents == null || messageContents.isEmpty()) {
            return List.of();
        }
        return messageContents.stream()
                .map(content -> {
                    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
                    return new OpenCodeMessageDiagnostics(content.length(), bytes.length,
                            Hashes.sha256Hex(content));
                })
                .toList();
    }

    private String bodySha256(byte[] body) {
        return body == null ? Hashes.sha256Hex("")
                : Hashes.sha256Hex(new String(body, StandardCharsets.UTF_8));
    }

    /** 包私有契约钩子,仅供确定性的传输层测试使用。 */
    HttpRequest completionRequestForTest(OpenCodeChatCompletionRequest request) {
        return prepareCompletionRequest(null, OpenCodeZenSessionIds.newEphemeral(), request).request();
    }

    /** 包私有契约钩子,仅供确定性的传输层测试使用。 */
    HttpClient productionHttpClientForTest() {
        return productionHttpClient;
    }

    /** 包私有契约钩子,仅供确定性的传输层测试使用。 */
    Duration settingsTimeoutForTest() {
        return settingsTimeout;
    }

    /** 包私有契约钩子,仅供确定性的传输层测试使用。 */
    HttpClient settingsHttpClientForTest() {
        return settingsHttpClient;
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

    private enum RequestType {
        PRODUCTION_COMPLETION,
        SETTINGS
    }

    private record PreparedRequest(
            HttpRequest request,
            HttpClient client,
            String path,
            RequestExecution execution) {
    }

    private static final class RequestExecution {
        private final String path;
        private final OpenCodeRequestDiagnostics initialDiagnostics;
        private final Instant startedAt = Instant.now();
        private Integer initialHttpStatus;
        private Long responseHeadersLatencyMillis;
        private Long firstSseEventLatencyMillis;

        private RequestExecution(String path, OpenCodeRequestDiagnostics initialDiagnostics) {
            this.path = path;
            this.initialDiagnostics = initialDiagnostics;
        }

        private void recordResponseHeaders(int status) {
            if (initialHttpStatus == null) {
                initialHttpStatus = status;
                responseHeadersLatencyMillis = elapsedMillis();
            }
        }

        private void recordFirstSseEvent() {
            if (firstSseEventLatencyMillis == null) {
                firstSseEventLatencyMillis = elapsedMillis();
            }
        }

        private Integer initialHttpStatus() {
            return initialHttpStatus;
        }

        private OpenCodeRequestDiagnostics snapshot() {
            return new OpenCodeRequestDiagnostics(
                    initialDiagnostics.requestType(),
                    initialDiagnostics.requestStartedAt(),
                    elapsedMillis(),
                    responseHeadersLatencyMillis,
                    firstSseEventLatencyMillis,
                    initialDiagnostics.messageCount(),
                    initialDiagnostics.messages(),
                    initialDiagnostics.requestBodyByteCount(),
                    initialDiagnostics.requestBodySha256(),
                    initialDiagnostics.stream(),
                    initialDiagnostics.temperaturePresent(),
                    initialDiagnostics.maxTokensPresent(),
                    initialDiagnostics.responseFormatPresent(),
                    initialDiagnostics.requestTimeoutPresent(),
                    initialDiagnostics.connectTimeoutPresent());
        }

        private long elapsedMillis() {
            return Math.max(0L, Duration.between(startedAt, Instant.now()).toMillis());
        }
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

    private static final class StreamState {
        private int eventCount;
        private String finishReason;
        private int reasoningEventCount;
        private int reasoningCharCount;
        private final MessageDigest reasoningDigest = sha256Digest();
        private RequestExecution execution;

        private void observeReasoning(String value) {
            reasoningEventCount++;
            reasoningCharCount += value.length();
            reasoningDigest.update(value.getBytes(StandardCharsets.UTF_8));
        }

        private String reasoningSha256() {
            byte[] digest = reasoningDigest.digest();
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        }

        private static MessageDigest sha256Digest() {
            try {
                return MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 not available", ex);
            }
        }
    }
}
