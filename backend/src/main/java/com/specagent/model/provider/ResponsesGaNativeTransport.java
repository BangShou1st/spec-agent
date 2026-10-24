package com.specagent.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.contract.CustomRuntimeSettingsPort;
import com.specagent.model.contract.FragmentListener;
import com.specagent.model.contract.GaModelContract;
import com.specagent.model.contract.GaModelTarget;
import com.specagent.model.contract.RuntimeCustomSettings;
import com.specagent.model.contract.StreamCancelledException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Custom RESPONSES transport for GA. Identity and safety checks mirror the
 * Chat Completions transport; requests never use previous_response_id and all
 * responses are fully parsed before tool calls become executable.
 */
@Component
public class ResponsesGaNativeTransport implements CustomGaTransport {
    private static final Logger log = LoggerFactory.getLogger(ResponsesGaNativeTransport.class);
    private final CustomRuntimeSettingsPort customSettings;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final AtomicReference<GaHttpSafetyDiagnostic> lastDiagnostic = new AtomicReference<>();

    @Autowired
    public ResponsesGaNativeTransport(CustomRuntimeSettingsPort customSettings, ObjectMapper mapper) {
        this(customSettings, mapper, ProviderHttpSupport.newClient(Duration.ofSeconds(10)));
    }

    public ResponsesGaNativeTransport(CustomRuntimeSettingsPort customSettings, ObjectMapper mapper, HttpClient client) {
        this.customSettings = customSettings;
        this.mapper = mapper;
        this.client = client;
    }

    public GaHttpSafetyDiagnostic lastSafetyDiagnostic() {
        return lastDiagnostic.get();
    }

    public GaModelContract.Response complete(GaModelTarget target, GaModelContract.Request request,
            Duration remainingBudget, BooleanSupplier active) {
        validateBudget(remainingBudget);
        if (!active.getAsBoolean()) throw new StreamCancelledException("GA execution no longer active");
        Resolved resolved = resolve(target);
        if (!resolved.nativeTools()) {
            ChatCompletionsGaTextTransport.requireTextOnly(request);
        }
        GaResponsesAdapter adapter = new GaResponsesAdapter();
        Map<String, Object> body = adapter.requestBody(request, target.model());
        try {
            ProviderHttpSupport.HttpResult result = ProviderHttpSupport.postJson(client, mapper,
                    resolved.endpoint(), resolved.headers(), body, budget(remainingBudget), resolved.context());
            GaModelContract.Response response = adapter.validateResponse(request, adapter.parse(result.json()));
            lastDiagnostic.set(GaHttpSafetyDiagnostic.capture(resolved.context(), target.model(),
                    "responses", 200, "NONE", "SUCCEEDED", request));
            return response;
        } catch (ModelProviderException ex) {
            lastDiagnostic.set(GaHttpSafetyDiagnostic.capture(resolved.context(), target.model(),
                    "responses", ex.httpStatus(), ex.gatewayCategory().name(), "UPSTREAM_REQUEST_FAILED", request));
            log.warn("GA Responses HTTP request failed diagnostic: {}", lastDiagnostic.get());
            throw ex;
        } catch (RuntimeException ex) {
            lastDiagnostic.set(GaHttpSafetyDiagnostic.capture(resolved.context(), target.model(),
                    "responses", null, ex.getClass().getSimpleName(), "LOCAL_FAILURE", request));
            throw ex;
        }
    }

    public GaModelContract.Response stream(GaModelTarget target, GaModelContract.Request request,
            Duration remainingBudget, BooleanSupplier active, FragmentListener listener) {
        if (listener == null) throw new IllegalArgumentException("GA Responses listener required");
        GaModelContract.Response response = complete(target, request, remainingBudget, active);
        if (!response.content().isEmpty() && !listener.onFragment(response.content())) {
            throw new StreamCancelledException("GA execution no longer active");
        }
        return response;
    }

    private static void validateBudget(Duration remainingBudget) {
        if (remainingBudget == null || remainingBudget.isNegative() || remainingBudget.isZero()
                || remainingBudget.compareTo(Duration.ofSeconds(180)) > 0) {
            throw new IllegalArgumentException("Invalid GA execution deadline");
        }
    }

    private static Duration budget(Duration remainingBudget) {
        return remainingBudget.compareTo(ProviderHttpSupport.INFERENCE_TIMEOUT) < 0
                ? remainingBudget : ProviderHttpSupport.INFERENCE_TIMEOUT;
    }

    private Resolved resolve(GaModelTarget target) {
        if (!"CUSTOM".equals(target.provider())) {
            throw new UnsupportedOperationException("UNSUPPORTED_AGENT_MODEL");
        }
        RuntimeCustomSettings settings = customSettings.requireRuntimeSettings(target.settingsRevision());
        if (!settings.selectedModel().equals(target.model())
                || !settings.bindingRevision().equals(target.settingsRevision())
                || !Objects.equals(settings.apiKey(), target.credential())) {
            throw new IllegalStateException("GA_MODEL_BINDING_MISMATCH");
        }
        if (CustomApiFormat.fromCode(settings.apiFormat()) != CustomApiFormat.RESPONSES) {
            throw new IllegalStateException("GA_MODEL_BINDING_MISMATCH");
        }
        String normalized = ProviderUrlSecurity.validateAndNormalizeBaseUrl(settings.baseUrl());
        boolean nativeTools = settings.providerId() != null && settings.nativeToolsVerified();
        if (nativeTools && (!settings.selectedModel().equals(target.model())
                || !target.settingsRevision().equals(settings.bindingRevision()))) {
            throw new IllegalStateException("GA_MODEL_BINDING_MISMATCH");
        }
        return new Resolved(ProviderUrlSecurity.canonicalEndpoint(normalized, CustomApiFormat.RESPONSES),
                new ResponsesProtocolAdapter().authHeaders(target.credential()), "custom-responses", nativeTools);
    }

    private record Resolved(String endpoint, Map<String, String> headers, String context, boolean nativeTools) {}
}
