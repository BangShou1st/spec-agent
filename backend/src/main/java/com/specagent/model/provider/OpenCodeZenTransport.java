package com.specagent.model.provider;

import java.util.List;

/**
 * Provider-specific HTTP protocol boundary for OpenCode Zen.
 *
 * <p>Owns the OpenCode Zen wire policy: base URL, User-Agent, bearer
 * authorization and JSON content type. Callers must never re-add a User-Agent
 * header; the transport applies it to every request, including credential
 * probes and model list requests, so the policy can never drift.
 *
 * <p>The transport is HTTP-only. It never reads the database, never resolves
 * credentials and never persists anything. It maps every failure into a
 * diagnosable {@link OpenCodeModelException} without leaking the API key.
 */
public interface OpenCodeZenTransport {

    /**
     * Base URL of the OpenCode Zen API, e.g. {@code https://opencode.ai/zen/v1}.
     */
    String BASE_URL = "https://opencode.ai/zen/v1";

    /**
     * OpenCode-compatible client identity for every HTTP request. This is the
     * single definition of the header; the transport applies it to
     * completion, model list and credential probe requests alike.
     */
    String USER_AGENT = "opencode/1.18.16";

    /**
     * Issues one chat completion against {@code POST /chat/completions}.
     *
     * @param apiKey  the OpenCode bearer credential; must not be blank
     * @param request the minimal chat completion payload
     * @return the parsed completion content plus optional usage fields
     */
    OpenCodeCompletionResponse complete(String apiKey, OpenCodeChatCompletionRequest request);

    /**
     * Fetches the current model list against {@code GET /models}.
     *
     * @param apiKey the optional bearer credential; may be null or blank because
     *               model discovery is public, but the transport still attaches
     *               the OpenCode User-Agent policy
     */
    OpenCodeModelList listModels(String apiKey);

    /**
     * Issues the bounded credential probe (a minimal completion) so a stored
     * key can be validated before being persisted. Shares the exact transport
     * policy of real completion requests.
     *
     * @param apiKey the OpenCode bearer credential to validate; must not be blank
     * @param model  the model the probe completion is issued against; chosen by
     *               the caller from the current model list, never hardcoded here
     */
    void validateCredential(String apiKey, String model);
}
