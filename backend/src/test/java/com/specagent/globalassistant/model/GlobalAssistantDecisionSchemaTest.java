package com.specagent.globalassistant.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.model.inference.ModelOutputContract;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Single-schema-source tests V2: the discriminated decision schema must mirror
 * the strict parser contract while the parser stays the executable authority.
 */
class GlobalAssistantDecisionSchemaTest {

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> branches() {
        return (List<Map<String, Object>>) GlobalAssistantDecisionSchema.schema().get("oneOf");
    }

    @Test
    void schemaIsDiscriminatedOnKind() {
        assertThat((List<String>) GlobalAssistantDecisionSchema.schema().get("required"))
                .containsExactly("kind");
        assertThat(branches()).hasSize(4);
    }

    @Test
    void toolBranchDiscriminatesCapability() {
        String schemaJson = toJson(GlobalAssistantDecisionSchema.schema());
        assertThat(schemaJson).contains("project.create");
        assertThat(schemaJson).contains("project.search");
        assertThat(schemaJson).contains("project.list_recent");
        assertThat(schemaJson).contains("project.get_summary");
    }

    @Test
    void branchesForbidAdditionalProperties() {
        for (Map<String, Object> branch : branches()) {
            assertThat(branch).containsEntry("additionalProperties", false);
        }
    }

    @Test
    void legacyFieldsAreNotInSchema() {
        String schemaJson = toJson(GlobalAssistantDecisionSchema.schema());
        assertThat(schemaJson).doesNotContain("statusText");
        assertThat(schemaJson).doesNotContain("requiresUserInput");
        assertThat(schemaJson).doesNotContain("\"done\"");
    }

    @Test
    void navigationDestinationsAreClosed() {
        String schemaJson = toJson(GlobalAssistantDecisionSchema.schema());
        assertThat(schemaJson).contains("PROJECT");
        assertThat(schemaJson).contains("PROJECTS");
        assertThat(schemaJson).contains("SKILLS");
        assertThat(schemaJson).contains("CONNECTIONS");
        assertThat(schemaJson).contains("SETTINGS");
    }

    @Test
    void contractExposesNamedJsonSchema() {
        ModelOutputContract.JsonSchema contract = GlobalAssistantDecisionSchema.contract();
        assertThat(contract.name()).isEqualTo(GlobalAssistantDecisionSchema.CONTRACT_NAME);
        assertThat(contract.schema()).isEqualTo(GlobalAssistantDecisionSchema.schema());
    }

    private static String toJson(Object value) {
        try {
            return new ObjectMapper().writeValueAsString(value);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void parserAcceptsContractShapedExample() {
        GlobalAssistantDecisionParser parser = new GlobalAssistantDecisionParser(new ObjectMapper());
        GlobalAssistantDecision decision = parser.parse(
                "{\"kind\":\"FINAL\",\"assistantText\":\"Hi.\"}");
        new GlobalAssistantDecisionValidator().validate(decision);
        assertThat(decision.kind()).isEqualTo(GlobalAssistantDecision.DecisionKind.FINAL);
    }

    @Test
    void parserStillRejectsUnknownFields() {
        GlobalAssistantDecisionParser parser = new GlobalAssistantDecisionParser(new ObjectMapper());
        assertThatThrownBy(() -> parser.parse("{\"kind\":\"FINAL\",\"assistantText\":\"Hi.\",\"extra\":1}"))
                .isInstanceOf(GlobalAssistantModelException.class);
    }

    @Test
    void parserRejectsLegacyFields() {
        GlobalAssistantDecisionParser parser = new GlobalAssistantDecisionParser(new ObjectMapper());
        assertThatThrownBy(() -> parser.parse("{\"kind\":\"FINAL\",\"assistantText\":\"Hi.\",\"done\":true}"))
                .isInstanceOf(GlobalAssistantModelException.class);
        assertThatThrownBy(() -> parser.parse("{\"kind\":\"FINAL\",\"assistantText\":\"Hi.\",\"statusText\":\"x\"}"))
                .isInstanceOf(GlobalAssistantModelException.class);
        assertThatThrownBy(() -> parser.parse("{\"kind\":\"FINAL\",\"assistantText\":\"Hi.\",\"requiresUserInput\":false}"))
                .isInstanceOf(GlobalAssistantModelException.class);
    }
}
