package com.specagent.testing;

import com.specagent.model.inference.ModelInferenceGateway;
import com.specagent.model.inference.ModelInferenceRequest;
import com.specagent.model.inference.ModelInferenceResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Deterministic test inference gateway selected only by the explicit test
 * profile. Returns the canonical fake model outputs shared with the Python
 * brain's fake model client (see {@code contracts/fixtures/fake-model-*.json})
 * so both language paths produce identical deterministic decisions.
 *
 * <p>Makes no HTTP calls and never sees a credential.
 */
@Component
@Profile("test")
@ConditionalOnProperty(name = "spec.agent.model.inference", havingValue = "fake")
public class FakeModelInferenceGateway implements ModelInferenceGateway {

    /** Must stay identical to contracts/fixtures/fake-model-state-update-output.json. */
    static final String STATE_UPDATE_OUTPUT = """
            {"claims":[{"kind":"goal","text":"The user clarified the main outcome.",\
            "status":"confirmed","confidence":0.9,"sourceRefs":[]}]}
            """;

    /** Must stay identical to contracts/fixtures/fake-model-decision-output.json. */
    static final String DECISION_OUTPUT = """
            {"observation":{"known":["The user clarified the main outcome."],\
            "unknowns":["The user must confirm scope boundaries."],"conflicts":[],"risks":[]},\
            "action":{"actionFamily":"REQUEST_USER_INPUT","payload":{"questionText":\
            "What is the most important outcome?","purpose":"This clarifies the primary \
requirement goal.","options":[{"label":"Clarify the primary goal"}],\
            "allowFreeAnswer":true},"sourceRefs":[]}}
            """;

    @Override
    public ModelInferenceResponse complete(ModelInferenceRequest request) {
        String content = switch (request.callType()) {
            case "STATE_UPDATE" -> STATE_UPDATE_OUTPUT;
            case "DECISION" -> DECISION_OUTPUT;
            default -> throw new IllegalArgumentException(
                    "FakeModelInferenceGateway does not support call type: " + request.callType());
        };
        return new ModelInferenceResponse(content, "stop", 0, 0);
    }
}
