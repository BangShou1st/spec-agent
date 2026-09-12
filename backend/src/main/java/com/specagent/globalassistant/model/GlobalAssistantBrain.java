package com.specagent.globalassistant.model;

import com.specagent.globalassistant.context.GlobalAssistantContext;
import com.specagent.model.inference.ModelInferenceGateway;
import com.specagent.model.inference.ModelInferenceRequest;
import com.specagent.model.inference.ModelInferenceResponse;
import com.specagent.model.inference.ModelOutputContract;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Dedicated Global Assistant inference path. Provider-neutral: only the
 * ModelInferenceGateway seam is used. No OpenCode settings/client, no
 * provider adapters, no second client, no retry/fallback, no function calling.
 *
 * <p>V2 production uses exactly one fixed output mode for all models and all
 * scenarios. No model-specific branch, no per-scenario switch, no semantic
 * retry switching modes.
 */
@Component
public class GlobalAssistantBrain {
    public static final String DECISION_CALL_TYPE = "GLOBAL_ASSISTANT_DECISION";
    public static final String DECISION_REPAIR_CALL_TYPE = "GLOBAL_ASSISTANT_DECISION_REPAIR";
    public static final String SUMMARY_CALL_TYPE = "GLOBAL_ASSISTANT_SUMMARY";
    private final GlobalAssistantPromptRenderer renderer;
    private final ModelInferenceGateway gateway;
    private final GlobalAssistantDecisionParser parser;
    private final GlobalAssistantDecisionValidator validator;
    public GlobalAssistantBrain(GlobalAssistantPromptRenderer renderer, ModelInferenceGateway gateway,
            GlobalAssistantDecisionParser parser, GlobalAssistantDecisionValidator validator) {
        this.renderer = renderer;
        this.gateway = gateway;
        this.parser = parser;
        this.validator = validator;
    }
    /**
     * Frozen V1 production output mode: JSON_OBJECT.
     * Single fixed contract for every model and scenario. No model branch,
     * no per-scenario switch, no silent fallback.
     */
    public static ModelOutputContract productionContract() {
        return ModelOutputContract.jsonObject();
    }
    public GlobalAssistantDecision decide(UUID runId, GlobalAssistantContext context,
            List<Map<String, Object>> observations) {
        return decide(runId, context, observations, productionContract());
    }
    /** Qualification / test path with an explicit contract. No silent fallback. */
    public GlobalAssistantDecision decide(UUID runId, GlobalAssistantContext context,
            List<Map<String, Object>> observations, ModelOutputContract contract) {
        return completeDecision(runId, DECISION_CALL_TYPE,
                renderer.render(context, observations), contract);
    }
    /**
     * Exactly one structural repair inference for a rejected semantic decision.
     * Same context, observations and production output contract; only a short
     * bounded rejection reason is added. Never loops internally.
     */
    public GlobalAssistantDecision repairDecision(UUID runId, GlobalAssistantContext context,
            List<Map<String, Object>> observations, String rejectionReason) {
        return completeDecision(runId, DECISION_REPAIR_CALL_TYPE,
                renderer.renderRepair(context, observations, rejectionReason), productionContract());
    }
    private GlobalAssistantDecision completeDecision(UUID runId, String callType,
            List<com.specagent.model.inference.ModelInferenceMessage> messages,
            ModelOutputContract contract) {
        ModelInferenceResponse response;
        try {
            response = gateway.complete(new ModelInferenceRequest(runId, callType, messages, 1024,
                    contract));
        } catch (com.specagent.model.provider.StreamCancelledException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new GlobalAssistantModelException("MODEL_UNAVAILABLE", "Model gateway unavailable", ex);
        }
        if (response == null || response.content() == null || response.content().isBlank()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "Empty model completion");
        }
        return parseAndValidate(response.content());
    }

    private GlobalAssistantDecision parseAndValidate(String content) {
        GlobalAssistantDecision decision = parser.parse(content);
        validator.validate(decision);
        return decision;
    }

    /**
     * Streaming variant of {@link #decide(UUID, GlobalAssistantContext, List)}.
     * Every real provider fragment first consults {@code shouldContinue},
     * so cancellation is polled even when the fragment carries no releasable
     * prose (e.g. TOOL JSON). Only releasable assistant prose (per contract
     * kind) reaches {@code fragmentSink}, never control JSON. The returned
     * decision comes from the same strict parse+validate path as the blocking variant.
     */
    public GlobalAssistantDecision decideStreaming(UUID runId, GlobalAssistantContext context,
            List<Map<String, Object>> observations, java.util.function.BooleanSupplier shouldContinue,
            com.specagent.model.provider.FragmentListener fragmentSink) {
        return completeDecisionStreaming(runId, DECISION_CALL_TYPE,
                renderer.render(context, observations), productionContract(), shouldContinue, fragmentSink);
    }

    /** Streaming variant of {@link #repairDecision(UUID, GlobalAssistantContext, List, String)}. */
    public GlobalAssistantDecision repairDecisionStreaming(UUID runId, GlobalAssistantContext context,
            List<Map<String, Object>> observations, String rejectionReason, java.util.function.BooleanSupplier shouldContinue,
            com.specagent.model.provider.FragmentListener fragmentSink) {
        return completeDecisionStreaming(runId, DECISION_REPAIR_CALL_TYPE,
                renderer.renderRepair(context, observations, rejectionReason), productionContract(),
                shouldContinue, fragmentSink);
    }

    private GlobalAssistantDecision completeDecisionStreaming(UUID runId, String callType,
            List<com.specagent.model.inference.ModelInferenceMessage> messages,
            ModelOutputContract contract, java.util.function.BooleanSupplier shouldContinue,
            com.specagent.model.provider.FragmentListener fragmentSink) {
        AssistantTextStreamDecoder decoder = new AssistantTextStreamDecoder();
        ModelInferenceResponse response;
        try {
            response = gateway.completeStreaming(new ModelInferenceRequest(runId, callType, messages,
                    1024, contract), fragment -> {
                // Flow control first: every provider fragment is a cancellation
                // checkpoint, even with no releasable prose. Presentation
                // emission stays a separate step below.
                if (!shouldContinue.getAsBoolean()) {
                    return false;
                }
                String releasable = decoder.append(fragment).releasableText();
                if (!releasable.isEmpty() && !fragmentSink.onFragment(releasable)) {
                    return false;
                }
                return true;
            });
        } catch (com.specagent.model.provider.StreamCancelledException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new GlobalAssistantModelException("MODEL_UNAVAILABLE", "Model gateway unavailable", ex);
        }
        if (response == null || response.content() == null || response.content().isBlank()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "Empty model completion");
        }
        return parseAndValidate(response.content());
    }
    public String summarize(UUID runId, String recentText) {
        ModelInferenceResponse response;
        try {
            response = gateway.complete(new ModelInferenceRequest(runId, SUMMARY_CALL_TYPE,
                    renderer.renderSummary(recentText), 512,
                    com.specagent.model.inference.ModelOutputContract.text()));
        } catch (RuntimeException ex) {
            throw new GlobalAssistantModelException("MODEL_UNAVAILABLE", "Summary model call failed", ex);
        }
        if (response == null || response.content() == null || response.content().isBlank()) {
            return null;
        }
        String summary = response.content().trim();
        return summary.length() <= 2000 ? summary : summary.substring(0, 2000);
    }
}
