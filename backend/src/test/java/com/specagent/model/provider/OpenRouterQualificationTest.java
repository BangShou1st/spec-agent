package com.specagent.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** Layered qualification: free identity, metadata capability, then probe. */
class OpenRouterQualificationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ObjectNode entry(String id, List<String> params, List<String> outModalities) {
        ObjectNode e = MAPPER.createObjectNode();
        e.put("id", id);
        if (params != null) {
            ArrayNode sp = MAPPER.createArrayNode();
            params.forEach(sp::add);
            e.set("supported_parameters", sp);
        }
        if (outModalities != null) {
            ObjectNode arch = MAPPER.createObjectNode();
            ArrayNode om = MAPPER.createArrayNode();
            outModalities.forEach(om::add);
            arch.set("output_modalities", om);
            e.set("architecture", arch);
        }
        return e;
    }

    private static List<String> params() {
        return List.of("temperature", "response_format", "tools");
    }

    @Test void freeCompatibleIncluded() {
        assertThat(OpenRouterModelQualification.isQualified(
                entry("qwen/qwen3:free", params(), List.of("text")))).isTrue();
    }

    @Test void freeWithoutStructuredSupportExcluded() {
        assertThat(OpenRouterModelQualification.isQualified(entry("nvidia/nemotron-3:free",
                List.of("temperature", "tools", "max_tokens"), List.of("text")))).isFalse();
    }

    @Test void embeddingAudioOnlyExcluded() {
        assertThat(OpenRouterModelQualification.isQualified(
                entry("cohere/embed:free", params(), List.of("embeddings")))).isFalse();
        assertThat(OpenRouterModelQualification.isQualified(
                entry("openai/whisper:free", params(), List.of("audio")))).isFalse();
    }

    @Test void paidCompatibleExcluded() {
        assertThat(OpenRouterModelQualification.isQualified(
                entry("openai/gpt-4o", params(), List.of("text")))).isFalse();
    }

    @Test void nameFreeButIdNotExcluded() {
        ObjectNode e = entry("openai/gpt-4o", params(), List.of("text"));
        e.put("name", "GPT-4o free special");
        assertThat(OpenRouterModelQualification.isQualified(e)).isFalse();
    }

    @Test void missingCapabilityMetadataExcluded() {
        assertThat(OpenRouterModelQualification.isQualified(
                entry("mystery/model:free", null, List.of("text")))).isFalse();
        assertThat(OpenRouterModelQualification.isQualified(
                entry("mystery/model:free", params(), null))).isFalse();
    }

    @Test void routerExemptButProbed() {
        ObjectNode e = MAPPER.createObjectNode();
        e.put("id", "openrouter/free");
        assertThat(OpenRouterModelQualification.isQualified(e)).isTrue();
    }
}
