package com.specagent.model.inference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Provider-neutral output contract tests. This file must never import a
 * provider package: the contract carries semantic shape only.
 */
class ModelOutputContractTest {

    @Test
    void textContractExists() {
        ModelOutputContract contract = ModelOutputContract.text();
        assertThat(contract).isInstanceOf(ModelOutputContract.Text.class);
    }

    @Test
    void jsonSchemaContractCarriesNameAndSchema() {
        Map<String, Object> schema = Map.of("type", "object", "additionalProperties", false);
        ModelOutputContract.JsonSchema contract =
                ModelOutputContract.jsonSchema("decision", schema);
        assertThat(contract.name()).isEqualTo("decision");
        assertThat(contract.schema()).containsEntry("type", "object");
    }

    @Test
    void jsonSchemaContractIsAnImmutableSnapshot() {
        Map<String, Object> schema = new HashMap<>(Map.of("type", "object"));
        ModelOutputContract.JsonSchema contract =
                ModelOutputContract.jsonSchema("decision", schema);
        schema.put("injected", true);
        assertThat(contract.schema()).doesNotContainKey("injected");
    }

    @Test
    void invalidJsonSchemaContractsFailClosed() {
        assertThatThrownBy(() -> ModelOutputContract.jsonSchema(null, Map.of("type", "object")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelOutputContract.jsonSchema("   ", Map.of("type", "object")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelOutputContract.jsonSchema("decision", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelOutputContract.jsonSchema("decision", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModelOutputContract.jsonSchema("decision", Map.of("type", "string")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void requestWithoutExplicitContractDefaultsToText() {
        ModelInferenceRequest request = new ModelInferenceRequest(UUID.randomUUID(), "DECISION",
                List.of(new ModelInferenceMessage("user", "hi")), 128);
        assertThat(request.outputContract()).isInstanceOf(ModelOutputContract.Text.class);
    }

    @Test
    void requestKeepsExplicitContract() {
        ModelOutputContract.JsonSchema contract =
                ModelOutputContract.jsonSchema("decision", Map.of("type", "object"));
        ModelInferenceRequest request = new ModelInferenceRequest(UUID.randomUUID(), "DECISION",
                List.of(new ModelInferenceMessage("user", "hi")), 128, contract);
        assertThat(request.outputContract()).isSameAs(contract);
    }
}
