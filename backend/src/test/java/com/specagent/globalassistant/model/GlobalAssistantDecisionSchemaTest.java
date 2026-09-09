package com.specagent.globalassistant.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.inference.ModelOutputContract;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Single-schema-source tests: the decision schema owner must mirror the
 * strict parser contract while the parser stays the executable authority.
 */
class GlobalAssistantDecisionSchemaTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties() {
        return (Map<String, Object>) GlobalAssistantDecisionSchema.schema().get("properties");
    }

    @Test
    void schemaCoversEveryDecisionField() {
        assertThat(properties()).containsKeys(
                "assistantText", "statusText", "toolRequest", "uiAction",
                "requiresUserInput", "done");
    }

    @Test
    void topLevelAndNestedObjectsForbidAdditionalProperties() {
        assertThat(GlobalAssistantDecisionSchema.schema()).containsEntry("additionalProperties", false);
        assertThat(((Map<String, Object>) properties().get("toolRequest")))
                .containsEntry("additionalProperties", false);
        assertThat(((Map<String, Object>) properties().get("uiAction")))
                .containsEntry("additionalProperties", false);
    }

    @Test
    void schemaRequiresTerminalFlags() {
        assertThat((List<String>) GlobalAssistantDecisionSchema.schema().get("required"))
                .contains("requiresUserInput", "done");
    }

    @Test
    void uiActionDestinationIsAClosedEnum() {
        Map<String, Object> uiAction = (Map<String, Object>) properties().get("uiAction");
        Map<String, Object> uiProperties = (Map<String, Object>) uiAction.get("properties");
        Map<String, Object> destination = (Map<String, Object>) uiProperties.get("destination");
        assertThat((List<String>) destination.get("enum"))
                .containsExactlyInAnyOrder("PROJECT", "PROJECTS", "SKILLS", "CONNECTIONS", "SETTINGS");
    }

    @Test
    void contractExposesNamedJsonSchema() {
        ModelOutputContract.JsonSchema contract = GlobalAssistantDecisionSchema.contract();
        assertThat(contract.name()).isEqualTo(GlobalAssistantDecisionSchema.CONTRACT_NAME);
        assertThat(contract.schema()).isEqualTo(GlobalAssistantDecisionSchema.schema());
    }

    @Test
    void parserAcceptsContractShapedExample() {
        GlobalAssistantDecisionParser parser = new GlobalAssistantDecisionParser(new ObjectMapper());
        GlobalAssistantDecision decision = parser.parse(
                "{\"assistantText\":\"Hi.\",\"statusText\":null,"
                        + "\"toolRequest\":null,\"uiAction\":null,"
                        + "\"requiresUserInput\":false,\"done\":true}");
        new GlobalAssistantDecisionValidator().validate(decision);
        assertThat(decision.done()).isTrue();
    }

    @Test
    void parserStillRejectsUnknownFields() {
        GlobalAssistantDecisionParser parser = new GlobalAssistantDecisionParser(new ObjectMapper());
        assertThatThrownBy(() -> parser.parse("{\"assistantText\":\"Hi.\",\"done\":true,\"extra\":1}"))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
}
