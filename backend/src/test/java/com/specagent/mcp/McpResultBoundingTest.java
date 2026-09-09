package com.specagent.mcp;

import com.specagent.mcp.transport.McpClientFactory;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Result bounding: giant tool payloads are truncated to the configured byte
 * budget with a truncation marker — they never flow unbounded into model
 * context.
 */
class McpResultBoundingTest {

    private final McpClientFactory factory = factoryWithBudget(200);

    private McpClientFactory factoryWithBudget(int bytes) {
        com.specagent.mcp.config.McpProperties properties =
                new com.specagent.mcp.config.McpProperties();
        properties.setResultMaxInlineBytes(bytes);
        return new McpClientFactory(properties,
                new com.specagent.common.network.OutboundNetworkPolicy(false));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> boundMap(Object content) throws Exception {
        Method method = McpClientFactory.class.getDeclaredMethod("boundMap", Object.class);
        method.setAccessible(true);
        return (Map<String, Object>) method.invoke(factory, content);
    }

    @Test
    void smallResultsPassThrough() throws Exception {
        Map<String, Object> bounded = boundMap(Map.of("value", "short summary"));
        assertThat(bounded).containsEntry("value", "short summary");
        assertThat(bounded).doesNotContainKey("_truncated");
    }

    @Test
    void giantResultsAreTruncatedWithMarker() throws Exception {
        String giant = "y".repeat(10_000);
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("value", giant);
        content.put("extra", "should-be-dropped");
        Map<String, Object> bounded = boundMap(content);
        int total = bounded.values().stream()
                .mapToInt(v -> String.valueOf(v)
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .sum();
        assertThat(total).isLessThanOrEqualTo(400);
        assertThat(String.valueOf(bounded)).contains("truncated");
    }
}
