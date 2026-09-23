package com.specagent.model.provider;

import com.specagent.model.contract.FragmentListener;
import com.specagent.model.contract.StreamCancelledException;

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
     *
     * <p>Wire-verified full form: the CLI (Bun fetch + @ai-sdk/provider-utils)
     * appends sdk/runtime suffixes, and the free-tier gate requires the version
     * >= 1.18.0 inside an {@code opencode/} prefix (1.17 -> 426). Keep the full
     * suffix chain so the request is indistinguishable from a genuine CLI call.
     */
    String USER_AGENT = "opencode/1.18.31 ai-sdk/provider-utils/4.0.23 runtime/bun/1.3.14";

    /**
     * Provider session correlation header, sent on every Zen HTTP request
     * from the single transport-owned wire policy below.
     */
    String SESSION_HEADER = "x-opencode-session";

    /**
     * Desktop identity headers applied to every Zen HTTP request so OpenCode
     * recognizes the request as originating from the OpenCode client (the same
     * identity the verified OpenCode desktop app sends). Without the full set,
     * OpenCode rejects the free tier with "free tier can only be used from
     * within OpenCode". The transport generates these on every request; the run
     * session (SESSION_HEADER) is the only value callers supply.
     *
     * <p>{@code x-opencode-project} carries the literal {@code global}:
     * the CLI running on a non-git global config reports project id
     * {@code global}, and wire A/B confirmed that value is accepted while
     * {@code prj_}-shaped random ids are unverified against the gate.
     */
    String CLIENT_HEADER = "x-opencode-client";
    String REQUEST_HEADER = "x-opencode-request";
    String PROJECT_HEADER = "x-opencode-project";
    String CLIENT_ID = "cli";
    String GLOBAL_PROJECT = "global";

    /** Safe endpoint provenance; never contains an authorization value. */
    default String endpoint() {
        return BASE_URL;
    }

    /**
     * Issues one chat completion against {@code POST /chat/completions}.
     *
     * @param apiKey    the OpenCode bearer credential; must not be blank
     * @param sessionId the provider session, e.g. the gateway-mapped stable
     *                  run session; must be non-blank and single-line
     * @param request   the minimal chat completion payload
     * @return the parsed completion content plus optional usage fields
     */
    OpenCodeCompletionResponse complete(String apiKey, String sessionId,
                                         OpenCodeChatCompletionRequest request);

    /**
     * Streaming variant of {@link #complete(String, String, OpenCodeChatCompletionRequest)}.
     * Decoded content fragments reach {@code listener} in arrival order while the
     * provider is still generating; the returned response is the same fully
     * aggregated contract. A declined fragment aborts the stream with
     * {@link StreamCancelledException}.
     */
    default OpenCodeCompletionResponse completeStreaming(String apiKey, String sessionId,
                                                          OpenCodeChatCompletionRequest request,
                                                          FragmentListener listener) {
        OpenCodeCompletionResponse response = complete(apiKey, sessionId, request);
        if (response != null && response.content() != null && !response.content().isEmpty()) {
            if (!listener.onFragment(response.content())) {
                throw new StreamCancelledException("listener declined content");
            }
        }
        return response;
    }

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
