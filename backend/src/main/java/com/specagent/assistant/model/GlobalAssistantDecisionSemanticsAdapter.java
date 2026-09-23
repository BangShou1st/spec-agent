package com.specagent.assistant.model;

import com.specagent.model.provider.CompatibilityDecisionSemantics;
import org.springframework.stereotype.Service;

/**
 * Bridges the provider-layer compatibility probe to the authoritative
 * assistant decision semantics. Pure delegation: parsing, validation, and
 * kind checking stay exactly where they are today.
 */
@Service
public class GlobalAssistantDecisionSemanticsAdapter implements CompatibilityDecisionSemantics {

    private final GlobalAssistantDecisionParser parser;
    private final GlobalAssistantDecisionValidator validator;

    public GlobalAssistantDecisionSemanticsAdapter(GlobalAssistantDecisionParser parser,
                                                   GlobalAssistantDecisionValidator validator) {
        this.parser = parser;
        this.validator = validator;
    }

    @Override
    public Object parseDecision(String content) throws Exception {
        return parser.parse(content);
    }

    @Override
    public void validateDecision(Object decision) throws Exception {
        validator.validate((GlobalAssistantDecision) decision);
    }

    @Override
    public boolean isFinal(Object decision) {
        return ((GlobalAssistantDecision) decision).kind()
                == GlobalAssistantDecision.DecisionKind.FINAL;
    }
}
