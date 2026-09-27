package com.specagent.model.inference;

import com.specagent.model.contract.ModelInferenceMessage;
import com.specagent.model.contract.ModelInferenceRequest;
import com.specagent.model.contract.ModelOutputContract;
import com.specagent.model.inference.ModelOutputContractTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * 文件名:ModelOutputContractTest.java
 *
 * 测试目标:验证与具体 Provider 无关的输出契约(文本、JSON Schema、JSON Object)的
 * 行为:契约的构造与不可变快照、非法参数一律 fail-closed 抛异常,以及请求未显式指定契约时默认使用文本契约。
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

    @Test
    void jsonObjectContractExists() {
        ModelOutputContract contract = ModelOutputContract.jsonObject();
        assertThat(contract).isInstanceOf(ModelOutputContract.JsonObject.class);
    }

    @Test
    void requestKeepsExplicitJsonObjectContract() {
        ModelOutputContract.JsonObject contract = ModelOutputContract.jsonObject();
        ModelInferenceRequest request = new ModelInferenceRequest(UUID.randomUUID(), "DECISION",
                List.of(new ModelInferenceMessage("user", "hi")), 128, contract);
        assertThat(request.outputContract()).isSameAs(contract);
    }
}
