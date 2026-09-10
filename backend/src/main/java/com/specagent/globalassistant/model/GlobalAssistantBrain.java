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
     * Single frozen production output contract. Qualification selects exactly
     * one of JSON_SCHEMA or JSON_OBJECT; production then uses it for every
     * decision call.
     */
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
        List<com.specagent.model.inference.ModelInferenceMessage> messages = renderer.render(context, observations);
        ModelInferenceResponse response;
        try {
            response = gateway.complete(new ModelInferenceRequest(runId, DECISION_CALL_TYPE, messages, 1024,
                    contract));
        } catch (RuntimeException ex) {
            throw new GlobalAssistantModelException("MODEL_UNAVAILABLE", "Model gateway unavailable", ex);
        }
        if (response == null || response.content() == null || response.content().isBlank()) {
            throw new GlobalAssistantModelException("MODEL_INVALID_RESPONSE", "Empty model completion");
        }
        GlobalAssistantDecision decision = parser.parse(response.content());
        validator.validate(decision);
        return decision;
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
