package com.specagent.assistant.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.assistant.runtime.GaExecutionStore;
import com.specagent.common.Hashes;
import com.specagent.model.contract.ActiveProviderPort;
import com.specagent.model.contract.GaModelContract;
import com.specagent.model.contract.ModelProvider;
import com.specagent.model.provider.OpenCodeZenSessionIds;
import com.specagent.model.provider.OpenCodeZenTransport;
import com.specagent.modelsettings.OpenCodeSettingsRepository;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;

/** Run-bound native broker orchestration; no Agent loop, credential forwarding or retries. */
@Service
public class GaNativeModelBroker {
    public record Result(int status, Object body) {}
    private final GaExecutionStore executions;
    private final ActiveProviderPort providers;
    private final OpenCodeSettingsRepository settings;
    private final OpenCodeZenTransport transport;
    private final ObjectMapper mapper = new ObjectMapper()
            .enable(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    public GaNativeModelBroker(GaExecutionStore executions, ActiveProviderPort providers,
            OpenCodeSettingsRepository settings, OpenCodeZenTransport transport) {
        this.executions = executions; this.providers = providers; this.settings = settings; this.transport = transport;
    }
    public GaModelContract.Response complete(GaModelContract.Request request) {
        if (request.stream()) throw new UnsupportedOperationException("UNSUPPORTED_AGENT_MODEL");
        return infer(request, null);
    }
    private GaModelContract.Response infer(GaModelContract.Request request,
            com.specagent.model.contract.FragmentListener listener) {
        var scope = new GaExecutionStore.Scope(request.runId(), request.executionEpoch(), request.leaseId());
        var binding = executions.authorize(scope);
        if (!binding.modelBindingId().equals(request.modelBindingId())) throw new IllegalStateException("GA_MODEL_BINDING_MISMATCH");
        if ((!"SUMMARY".equals(request.callType()) && !binding.allowedTools().equals(request.tools()))
                || ("SUMMARY".equals(request.callType()) && !request.tools().isEmpty()))
            throw new IllegalStateException("GA_MODEL_CATALOG_MISMATCH");
        // Resolve one immutable host settings snapshot. A changed target/revision fails; never substitute.
        if (providers.activeProvider() != ModelProvider.OPENCODE_ZEN) throw new IllegalStateException("GA_MODEL_BINDING_MISMATCH");
        var target = settings.find().orElseThrow(() -> new IllegalStateException("GA_MODEL_NOT_CONFIGURED"));
        if (!binding.model().equals(target.selectedModel())
                || !binding.settingsRevision().equals("opencode-settings:" + target.updatedAt()))
            throw new IllegalStateException("GA_MODEL_BINDING_MISMATCH");
        String requestHash = Hashes.sha256Hex(json(request));
        var reservation = executions.reserve(scope, request.callId(), "MODEL", requestHash);
        if (!reservation.fresh()) {
            if ("SUCCEEDED".equals(reservation.status())) return GaModelContract.readResponse(reservation.result());
            throw new IllegalStateException("GA_CALL_IN_PROGRESS_OR_UNKNOWN");
        }
        try {
            Duration remaining = Duration.between(Instant.now(), binding.deadline());
            if (remaining.isZero() || remaining.isNegative()) throw new IllegalStateException("GA_EXECUTION_FENCE");
            var response = listener == null
                    ? transport.completeNativeGa(target.apiKey(), OpenCodeZenSessionIds.forConversation(binding.threadId()),
                        request, binding.model(), remaining, () -> executions.active(scope))
                    : transport.streamNativeGa(target.apiKey(), OpenCodeZenSessionIds.forConversation(binding.threadId()),
                        request, binding.model(), remaining, () -> executions.active(scope),
                        text -> executions.active(scope) && listener.onFragment(text));
            new com.specagent.model.provider.GaChatCompletionsAdapter().validateResponse(request,response);
            if(response.toolCalls().size()>1) {
                var approved=executions.catalog(scope).descriptors().stream()
                    .filter(d->d.readOnly() && d.sideEffectClass().equals("NONE") && !java.util.Set.of("ui.navigate","user-input.request").contains(d.capabilityId()))
                    .map(com.specagent.assistant.tool.GaCatalogProjection.Descriptor::name).collect(java.util.stream.Collectors.toSet());
                if(response.toolCalls().stream().anyMatch(call->!approved.contains(call.name()))) throw new IllegalArgumentException("GA batch must contain only admitted read-only business tools");
            }
            executions.complete(scope, request.callId(), json(response));
            return response;
        } catch (RuntimeException ex) {
            executions.unknown(scope, request.callId());
            throw ex;
        }
    }
    /** Writes only typed host events, never provider JSON/SSE or partially parsed arguments. */
    public void stream(String body, java.io.OutputStream output) throws java.io.IOException {
        var request = GaModelContract.readRequest(body);
        if (!request.stream() || !"AGENT".equals(request.callType())) throw new IllegalArgumentException("GA stream request");
        var sequence = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.BiConsumer<String,Object> emit = (type,payload) -> {
            if ("TEXT_DELTA".equals(type) && sequence.get() >= 8191)
                throw new IllegalArgumentException("GA model stream limit"); // Reserve a slot for typed failure.
            int next = sequence.incrementAndGet();
            if (next > 8192) throw new IllegalArgumentException("GA model stream limit");
            try {
                output.write((json(java.util.Map.of("protocolVersion","ga-model-stream.v1", "callId",request.callId(),
                        "sequence",next,"type",type,"payload",payload))+"\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                output.flush();
            } catch (java.io.IOException ex) { throw new java.io.UncheckedIOException(ex); }
        };
        try {
            var result = infer(request, text -> { emit.accept("TEXT_DELTA",java.util.Map.of("text",text)); return true; });
            emit.accept("COMPLETED",result); // Ledger commit precedes release of executable calls.
        } catch (java.io.UncheckedIOException disconnected) { throw disconnected.getCause(); }
        catch (RuntimeException failure) { emit.accept("FAILED",java.util.Map.of("errorCode","GA_MODEL_STREAM_FAILED")); }
    }
    public Result invoke(String body) {
        try {
            var response = complete(GaModelContract.readRequest(body));
            return new Result(200, mapper.convertValue(response, java.util.Map.class));
        } catch (com.specagent.model.contract.ModelGatewayException ex) {
            return failure(502, "GA_MODEL_" + ex.gatewayCategory().name());
        } catch (com.specagent.model.contract.StreamCancelledException ex) {
            return failure(409, "GA_EXECUTION_FENCE");
        } catch (UnsupportedOperationException ex) { return failure(422, "UNSUPPORTED_AGENT_MODEL"); }
        catch (IllegalArgumentException ex) { return failure(400, "GA_MODEL_PROTOCOL_ERROR"); }
        catch (IllegalStateException ex) {
            String code = java.util.Set.of("GA_EXECUTION_FENCE", "GA_MODEL_BINDING_MISMATCH", "GA_MODEL_CATALOG_MISMATCH",
                    "GA_MODEL_NOT_CONFIGURED", "GA_CALL_IN_PROGRESS_OR_UNKNOWN", "GA_CALL_ID_CONFLICT",
                    "GA_BUDGET_EXHAUSTED", "GA_CALL_NOT_RESERVED").contains(ex.getMessage())
                    ? ex.getMessage() : "GA_HOST_STATE_ERROR";
            return failure(409, code);
        }
    }
    private Result failure(int status, String code) { return new Result(status, java.util.Map.of("errorCode", code)); }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalArgumentException("Invalid GA model payload"); }
    }
}
