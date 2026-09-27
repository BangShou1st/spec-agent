package com.specagent.model.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * 文件名:OpenRouterQualificationTest.java
 *
 * 测试目标:验证 OpenRouter 模型的分层准入规则:先看免费身份(仅 id 后缀 :free),
 * 再看元数据能力(需支持 response_format 等参数、输出模态为文本),最后由线上探测确认。
 * 覆盖免费且兼容的纳入、缺结构化输出/纯嵌入或音频/付费模型/显示名免费而 id 不免费/
 * 能力元数据缺失等情况的排除,以及 openrouter/free 豁免元数据但仍需探测。
 */
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
