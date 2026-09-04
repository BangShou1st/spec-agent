package com.specagent.trace;

import com.specagent.agent.contract.AgentContracts;
import com.specagent.agent.contract.AgentInputSnapshot;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AgentResponseEnvelope;
import com.specagent.agent.policy.PolicyDecision;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional, in-process diagnostic recorder used by the evaluation plumbing.
 * It is off by default, never writes to the inference request, and keeps one
 * immutable snapshot per attempt until the evaluation runner takes it.
 */
@Service
public class SemanticTraceRecorder {

    private final AtomicBoolean enabled;
    private final Map<UUID, SemanticTrace> traces = new ConcurrentHashMap<>();

    @Autowired
    public SemanticTraceRecorder(
            @Value("${spec.agent.semantic-trace.enabled:false}") boolean enabled) {
        this.enabled = new AtomicBoolean(enabled);
    }

    /** Factory for focused deterministic tests without Spring configuration. */
    public static SemanticTraceRecorder forTesting(boolean enabled) {
        return new SemanticTraceRecorder(enabled);
    }

    public boolean enabled() {
        return enabled.get();
    }

    /** Test-only toggle used to compare the same deterministic flow ON/OFF. */
    public void setEnabledForTesting(boolean enabled) {
        this.enabled.set(enabled);
    }

    public void captureStateUpdateInput(AgentRequestEnvelope request) {
        captureInput("STATE_UPDATE_INPUT", request);
    }

    public void captureDecisionInput(AgentRequestEnvelope request) {
        captureInput("DECISION_INPUT", request);
    }

    public void captureStateUpdateOutput(AgentResponseEnvelope response) {
        captureOutput("STATE_UPDATE_OUTPUT", response, "stateUpdate");
    }

    public void captureDecisionOutput(AgentResponseEnvelope response) {
        captureOutput("DECISION_OUTPUT", response, "actionProposal");
    }

    public void capturePolicyDecision(UUID runId, PolicyDecision decision) {
        if (!shouldCapture(runId) || decision == null) {
            return;
        }
        Map<String, Object> policy = new LinkedHashMap<>();
        policy.put("classification", decision.classification().name());
        policy.put("auto_execute", decision.autoExecute());
        policy.put("requires_confirmation", decision.requiresConfirmation());
        policy.put("deny_reason", decision.denyReason());
        append(runId, "POLICY_DECISION", policy);
    }

    public void capturePostState(UUID runId, AgentInputSnapshot postState) {
        if (!shouldCapture(runId)) {
            return;
        }
        Map<String, Object> projection = asMap(postState);
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("state_projection", projection);
        stage.put("effective_claims", projection.getOrDefault("effectiveClaims", java.util.List.of()));
        stage.put("route_context", projection.get("routeContext"));
        stage.put("semantic_fingerprint", fingerprintForSnapshot(postState));
        append(runId, "POST_STATE_UPDATE_STATE", stage);
    }

    public void captureFailure(UUID runId, String stage, Throwable error) {
        if (!shouldCapture(runId)) {
            return;
        }
        Map<String, Object> failure = new LinkedHashMap<>();
        failure.put("error_type", error == null ? "unknown" : error.getClass().getSimpleName());
        failure.put("error_summary", SemanticTraceSanitizer.exceptionSummary(error));
        append(runId, stage, failure);
    }

    /** Takes and removes one attempt. A missing trace is an explicit empty trace. */
    public SemanticTrace take(UUID runId) {
        if (!enabled() || runId == null) {
            return SemanticTrace.empty(runId);
        }
        SemanticTrace trace = traces.remove(runId);
        return trace == null ? SemanticTrace.empty(runId) : trace;
    }

    /** Returns a snapshot without removing it; useful for unit tests. */
    public SemanticTrace snapshot(UUID runId) {
        return enabled() && runId != null
                ? traces.getOrDefault(runId, SemanticTrace.empty(runId))
                : SemanticTrace.empty(runId);
    }

    private void captureInput(String stageName, AgentRequestEnvelope request) {
        if (!shouldCapture(request == null ? null : request.runId())) {
            return;
        }
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("runtime_request", requestMap(request));
        stage.put("semantic_fingerprint", SemanticFingerprint.forRequest(request));
        append(request.runId(), stageName, stage);
    }

    private void captureOutput(String stageName, AgentResponseEnvelope response,
                               String normalizedField) {
        if (!shouldCapture(response == null ? null : response.runId())) {
            return;
        }
        Map<String, Object> responseMap = responseMap(response);
        Map<String, Object> stage = new LinkedHashMap<>();
        stage.put("normalized_output", responseMap.get(normalizedField));
        stage.put("observation", responseMap.get("observation"));
        stage.put("usage", responseMap.get("usage"));
        stage.put("semantic_fingerprint", outputFingerprint(
                responseMap, normalizedField));
        append(response.runId(), stageName, stage);

        Object rawDiagnostic = response.diagnostics().get("semanticTrace");
        if (rawDiagnostic instanceof Map<?, ?> diagnostic) {
            Map<String, Object> inputStage = new LinkedHashMap<>(
                    traces.getOrDefault(response.runId(), SemanticTrace.empty(response.runId()))
                            .stages().getOrDefault(stageName.replace("OUTPUT", "INPUT"), Map.of()));
            inputStage.put("model_input", diagnostic.get("modelInput"));
            Map<String, Object> prompt = new LinkedHashMap<>();
            prompt.put("system_prompt_sha256", diagnostic.get("systemPromptSha256"));
            prompt.put("user_prompt_sha256", diagnostic.get("userPromptSha256"));
            inputStage.put("prompt", prompt);
            append(response.runId(), stageName.replace("OUTPUT", "INPUT"), inputStage);
        }
    }

    private void append(UUID runId, String stage, Map<String, Object> value) {
        traces.compute(runId, (ignored, current) ->
                (current == null ? SemanticTrace.empty(runId) : current).withStage(stage, value));
    }

    private boolean shouldCapture(UUID runId) {
        return enabled() && runId != null;
    }

    private static Map<String, Object> requestMap(AgentRequestEnvelope request) {
        return AgentContracts.read(AgentContracts.write(request), Map.class);
    }

    private static Map<String, Object> responseMap(AgentResponseEnvelope response) {
        return AgentContracts.read(AgentContracts.write(response), Map.class);
    }

    private static Map<String, Object> asMap(AgentInputSnapshot snapshot) {
        return AgentContracts.read(AgentContracts.write(snapshot), Map.class);
    }

    private static String fingerprintForSnapshot(AgentInputSnapshot snapshot) {
        // The post-state fingerprint intentionally uses the same normalized
        // semantic fields as a request, while omitting the request event.
        return SemanticFingerprint.forSnapshot(snapshot);
    }

    private static String outputFingerprint(Map<String, Object> response,
                                            String normalizedField) {
        Map<String, Object> semantic = new LinkedHashMap<>();
        if ("actionProposal".equals(normalizedField)) {
            Map<String, Object> proposal = asMap(response.get(normalizedField));
            semantic.put("actionFamily", proposal.get("actionFamily"));
            semantic.put("payload", proposal.get("payload"));
            semantic.put("sourceRefs", proposal.get("sourceRefs"));
            semantic.put("anchorRefs", proposal.get("anchorRefs"));
            semantic.put("observation", response.get("observation"));
        } else {
            semantic.put(normalizedField, response.get(normalizedField));
        }
        return com.specagent.common.Hashes.sha256Hex(AgentContracts.write(semantic));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            result.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return result;
    }

}
