package com.specagent.model.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Shared bounded HTTP execution for OpenRouter / Custom. Never follows
 * redirects (credentials must never forward to another origin), enforces
 * body-size and timeout bounds, and redacts secrets from every message.
 */
public final class ProviderHttpSupport {

    public static final int MAX_BODY_BYTES = 5 * 1024 * 1024;
    public static final Duration INFERENCE_TIMEOUT = Duration.ofSeconds(90);
    public static final Duration SETTINGS_TIMEOUT = Duration.ofSeconds(20);

    /**
     * Centralized SSE stream budgets. A single event, the aggregated
     * visible output, and the total raw streamed chars each fail closed
     * instead of growing without bound. No payload is ever dumped.
     */
    public static final int MAX_SSE_EVENT_BYTES = 256 * 1024;
    public static final int MAX_AGGREGATED_BYTES = 2 * 1024 * 1024;
    public static final int MAX_STREAM_RAW_BYTES = 8 * 1024 * 1024;

    private ProviderHttpSupport() {
    }

    public static HttpClient newClient(Duration connectTimeout) {
        return HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout)
                .build();
    }

    public record HttpResult(int status, JsonNode json, String snippet) {
    }

    public static HttpResult postJson(HttpClient client, ObjectMapper mapper, String url,
                                      Map<String, String> headers, Map<String, Object> body,
                                      Duration timeout, String context) {
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(body);
        } catch (Exception ex) {
            throw ModelProviderException.invalidResponse(context, "Failed to encode provider request", ex);
        }
        if (payload.length > MAX_BODY_BYTES) {
            throw ModelProviderException.invalidResponse(context, "Provider request too large");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        headers.forEach((k, v) -> {
            if (v != null) {
                builder.header(k, v);
            }
        });
        HttpResponse<byte[]> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException | SocketTimeoutException ex) {
            throw ModelProviderException.timeout(context, "Provider request timed out", ex);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw ModelProviderException.connection(context, "Provider connection failed", ex);
        }
        byte[] raw = response.body() == null ? new byte[0] : response.body();
        if (raw.length > MAX_BODY_BYTES) {
            throw ModelProviderException.invalidResponse(context, "Provider response too large");
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        JsonNode json = null;
        if (!text.isBlank()) {
            try {
                json = mapper.readTree(text);
            } catch (Exception ex) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw mapStatus(response.statusCode(), safeSnippet(text), context);
                }
                throw ModelProviderException.invalidResponse(context, "Provider returned malformed JSON", ex);
            }
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw mapStatus(response.statusCode(), safeSnippet(text), context);
        }
        if (json == null) {
            throw ModelProviderException.invalidResponse(context, "Provider returned empty response");
        }
        return new HttpResult(response.statusCode(), json, safeSnippet(text));
    }

    public static HttpResult getJson(HttpClient client, ObjectMapper mapper, String url,
                                     Map<String, String> headers, Duration timeout, String context) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(timeout)
                .GET();
        headers.forEach((k, v) -> {
            if (v != null) {
                builder.header(k, v);
            }
        });
        HttpResponse<byte[]> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (HttpTimeoutException | SocketTimeoutException ex) {
            throw ModelProviderException.timeout(context, "Provider request timed out", ex);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw ModelProviderException.connection(context, "Provider connection failed", ex);
        }
        byte[] raw = response.body() == null ? new byte[0] : response.body();
        if (raw.length > MAX_BODY_BYTES) {
            throw ModelProviderException.invalidResponse(context, "Provider response too large");
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        // Model-list 404/405/501 map to unsupported (manual fallback) at the
        // caller; other errors stay hard failures so auth outages are visible.
        if (response.statusCode() == 404 || response.statusCode() == 405 || response.statusCode() == 501) {
            return new HttpResult(response.statusCode(), null, safeSnippet(text));
        }
        JsonNode json = null;
        if (!text.isBlank()) {
            try {
                json = mapper.readTree(text);
            } catch (Exception ex) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw mapStatus(response.statusCode(), safeSnippet(text), context);
                }
                throw ModelProviderException.invalidResponse(context, "Provider returned malformed JSON", ex);
            }
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw mapStatus(response.statusCode(), safeSnippet(text), context);
        }
        if (json == null) {
            throw ModelProviderException.invalidResponse(context, "Provider returned empty response");
        }
        return new HttpResult(response.statusCode(), json, safeSnippet(text));
    }

    /**
     * True SSE with incremental fragments. Every provider event is a
     * cancellation checkpoint via an empty fragment when it carries no
     * visible text, so Stop/Steer interrupts even before the first prose.
     */
    public static String postSse(HttpClient client, ObjectMapper mapper, String url,
                                 Map<String, String> headers, Map<String, Object> body,
                                 ProtocolAdapter adapter, String context,
                                 FragmentListener listener) {
        byte[] payload;
        try {
            payload = mapper.writeValueAsBytes(body);
        } catch (Exception ex) {
            throw ModelProviderException.invalidResponse(context, "Failed to encode provider request", ex);
        }
        if (payload.length > MAX_BODY_BYTES) {
            throw ModelProviderException.invalidResponse(context, "Provider request too large");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(INFERENCE_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload));
        headers.forEach((k, v) -> {
            if (v != null) {
                builder.header(k, v);
            }
        });
        HttpResponse<InputStream> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException | SocketTimeoutException ex) {
            throw ModelProviderException.timeout(context, "Provider streaming request timed out", ex);
        } catch (IOException | InterruptedException ex) {
            if (ex instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw ModelProviderException.connection(context, "Provider connection failed", ex);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String snippet = readSnippet(response.body());
            throw mapStatus(response.statusCode(), snippet, context);
        }
        StringBuilder aggregated = new StringBuilder();
        StringBuilder eventData = new StringBuilder();
        boolean successTerminal = false;
        long rawChars = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                rawChars += line.length() + 1;
                if (rawChars > MAX_STREAM_RAW_BYTES) {
                    throw ModelProviderException.invalidResponse(context, "Stream exceeded size budget");
                }
                if (line.isEmpty()) {
                    if (eventData.length() == 0) {
                        continue;
                    }
                    successTerminal = handleSseEvent(mapper, adapter, context, listener, eventData.toString(), aggregated);
                    eventData.setLength(0);
                    if (successTerminal) {
                        break;
                    }
                } else if (line.startsWith("data:")) {
                    String data = line.substring(5);
                    if (data.startsWith(" ")) {
                        data = data.substring(1);
                    }
                    if (eventData.length() > 0) {
                        eventData.append('\n');
                    }
                    eventData.append(data);
                    if (eventData.length() > MAX_SSE_EVENT_BYTES) {
                        throw ModelProviderException.invalidResponse(context, "Event exceeded size budget");
                    }
                }
            }
            if (!successTerminal && eventData.length() > 0) {
                successTerminal = handleSseEvent(mapper, adapter, context, listener, eventData.toString(), aggregated);
            }
        } catch (ModelProviderException | StreamCancelledException ex) {
            throw ex;
        } catch (IOException ex) {
            throw ModelProviderException.connection(context, "Provider stream interrupted", ex);
        }
        if (!successTerminal) {
            throw ModelProviderException.invalidResponse(context, "Stream ended without protocol terminal");
        }
        if (aggregated.length() == 0) {
            throw ModelProviderException.invalidResponse(context, "Provider returned empty stream");
        }
        return aggregated.toString();
    }

    private static boolean handleSseEvent(ObjectMapper mapper, ProtocolAdapter adapter, String context,
                                          FragmentListener listener, String rawData, StringBuilder aggregated) {
        String trimmed = rawData.trim();
        if (trimmed.isEmpty()) {
            checkpoint(listener);
            return false;
        }
        JsonNode data;
        boolean doneLiteral = "[DONE]".equals(trimmed);
        if (doneLiteral) {
            data = null;
        } else {
            try {
                data = mapper.readTree(trimmed);
            } catch (Exception ex) {
                throw ModelProviderException.invalidResponse(context, "Malformed stream event", ex);
            }
        }
        if (!doneLiteral && adapter.isFailureTerminal(data, context)) {
            try {
                adapter.extractVisibleText(data, context);
            } catch (ModelProviderException ex) {
                throw ex;
            }
            throw ModelProviderException.providerRequestError(context, "Provider stream reported failure", null);
        }
        boolean success = adapter.isSuccessfulTerminal(trimmed, data, context);
        if (data == null) {
            checkpoint(listener);
            return success;
        }
        String visible = adapter.extractVisibleText(data, context);
        if (visible != null && !visible.isEmpty()) {
            aggregated.append(visible);
            if (aggregated.length() > MAX_AGGREGATED_BYTES) {
                throw ModelProviderException.invalidResponse(context, "Stream output exceeded size budget");
            }
            if (!listener.onFragment(visible)) {
                throw new StreamCancelledException("Provider stream cancelled by run owner");
            }
        } else {
            // Cancellation checkpoint even with no visible prose.
            checkpoint(listener);
        }
        return success;
    }

    private static void checkpoint(FragmentListener listener) {
        if (!listener.onFragment("")) {
            throw new StreamCancelledException("Provider stream cancelled by run owner");
        }
    }

    private static ModelProviderException mapStatus(int status, String snippet, String context) {
        return HttpErrorShapes.map(status, snippet, context);
    }

    private static String safeSnippet(String s) {
        if (s == null) {
            return "";
        }
        String redacted = s.replaceAll("(?i)Bearer\\s+\\S+", "Bearer <redacted>")
                .replaceAll("(?i)sk-[A-Za-z0-9._-]+", "sk-<redacted>");
        return redacted.length() > 300 ? redacted.substring(0, 300) : redacted;
    }

    private static String readSnippet(InputStream in) {
        if (in == null) {
            return "";
        }
        try {
            byte[] buf = in.readNBytes(4096);
            return safeSnippet(new String(buf, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return "";
        }
    }
}
