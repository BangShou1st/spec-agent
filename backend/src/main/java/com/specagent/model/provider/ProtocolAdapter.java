package com.specagent.model.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.specagent.model.inference.ModelInferenceRequest;
import com.specagent.model.inference.ModelInferenceResponse;
import java.util.List;
import java.util.Map;

/**
 * High-cohesion wire translation for one API format.
 *
 * <p>Provider-neutral in, provider-neutral out. Never executes Agent tools,
 * never touches the database, never knows OPENROUTER / CUSTOM routing.
 */
public interface ProtocolAdapter {

    CustomApiFormat format();

    /** Provider-neutral request to wire body. Never includes credentials. */
    Map<String, Object> buildRequestBody(ModelInferenceRequest request, String model);

    /** Streaming variant adds {@code stream=true} without changing semantics. */
    default Map<String, Object> buildStreamRequestBody(ModelInferenceRequest request, String model) {
        Map<String, Object> body = new java.util.LinkedHashMap<>(buildRequestBody(request, model));
        body.put("stream", true);
        // Anthropic uses stream:true as well; Chat/Responses use stream:true.
        return body;
    }

    /** Auth wire format. Empty when key is absent (local no-auth services). */
    Map<String, String> authHeaders(String apiKey);

    /** Non-stream response extraction. Whitelist only final text. */
    ModelInferenceResponse parseNonStreamResponse(JsonNode root, String context);

    /**
     * SSE {@code data:} payload to visible text. Returns null when the event
     * carries no assistant presentation text (reasoning, usage, lifecycle).
     * Throws {@link ModelProviderException} on protocol error events.
     */
    String extractVisibleText(JsonNode data, String context);

    /** True when this SSE data event terminates the stream. */
    boolean isTerminalData(String rawData, JsonNode data, String context);

    /**
     * True when this SSE data event is a protocol-recognized successful
     * terminal. A stream is successful ONLY after such an event; TCP/HTTP
     * EOF with visible text but no success terminal is a truncated stream
     * and must fail.
     */
    default boolean isSuccessfulTerminal(String rawData, JsonNode data, String context) {
        return isTerminalData(rawData, data, context) && !isFailureTerminal(data, context);
    }

    /**
     * True when this SSE data event is a protocol-recognized failure
     * terminal (provider error, incomplete, cancelled). Never returns
     * accumulated partial text as success.
     */
    default boolean isFailureTerminal(JsonNode data, String context) {
        return false;
    }

    /** Parses {@code GET <base>/models} payload to candidate ids. */
    List<String> parseModelList(JsonNode root, String context);

    /** Maps HTTP error status + optional body to neutral failure. */
    ModelProviderException mapHttpError(int httpStatus, String bodySnippet, String context);
}
