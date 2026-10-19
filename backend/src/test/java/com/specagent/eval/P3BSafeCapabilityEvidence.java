package com.specagent.eval;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Exports only allowlisted, deterministic capability facts to P3-B evaluation reports. */
final class P3BSafeCapabilityEvidence {

    private static final String MEMORY_SEARCH_ID = "memory.search";
    private static final Set<String> SAFE_ARGUMENT_NAMES =
            Set.of("query", "scope", "routeRef", "maxResults");

    private P3BSafeCapabilityEvidence() {
    }

    static Map<String, Object> summarize(List<?> descriptors, P3BCorpus.P3BScenario scenario) {
        Map<String, Object> memorySearch = findMemorySearch(descriptors);
        boolean visible = !memorySearch.isEmpty();
        SchemaFacts schema = schemaFacts(asMap(memorySearch.get("inputSchema")));
        List<String> provided = providedArgumentNames(scenario);
        List<String> missing = missingArguments(schema.requiredArguments(), provided);
        List<Map<String, Object>> safeCapabilities = visible
                ? List.of(Map.of("id", MEMORY_SEARCH_ID,
                        "required_arguments", schema.verified() ? schema.requiredArguments() : List.of()))
                : List.of();

        return Map.of(
                "available_capabilities", safeCapabilities,
                "memory_search_visible", visible,
                "memory_search_query_required", schema.verified()
                        && schema.requiredArguments().contains("query"),
                "memory_search_missing_arguments", missing,
                "memory_search_provided_argument_names", provided,
                "required_arguments_verified", schema.verified());
    }

    /**
     * "已提供的参数名"的**唯一**取值口径:一律优先取场景显式声明的、由模型可见
     * 输入承载的已知参数({@code modelVisibleProvidedArguments});只有在场景没有
     * 做这种声明时才回退到历史行为(读取夹具脚本化决策的 payload)。
     *
     * <p>这条优先级是修复 C09 公平性的关键:live 链路不执行夹具脚本,脚本里的
     * {@code scope} 参数因此**不能**当作模型已知的事实。新自然语言场景直接声明
     * {@code ["scope"]},并由离线测试断言该声明确实由可见任务文本承载。
     */
    private static List<String> providedArgumentNames(P3BCorpus.P3BScenario scenario) {
        if (scenario == null) return List.of();
        if (!scenario.modelVisibleProvidedArguments().isEmpty()) {
            return scenario.modelVisibleProvidedArguments().stream()
                    .filter(SAFE_ARGUMENT_NAMES::contains)
                    .sorted()
                    .toList();
        }
        return fixtureProvidedArgumentNames(scenario);
    }

    static Map<String, Object> argumentAudit(Map<String, Object> snapshot,
                                             String capabilityId,
                                             Map<String, Object> arguments) {
        if (capabilityId == null || capabilityId.isBlank()) {
            return Map.of("status", "NOT_APPLICABLE");
        }
        if (!MEMORY_SEARCH_ID.equals(capabilityId)) {
            // Do not export unknown capability IDs, descriptions, or schema fields.
            return Map.of("status", "UNRESOLVED");
        }

        Map<String, Object> descriptor = findMemorySearch(asList(snapshot.get("availableCapabilities")));
        if (descriptor.isEmpty()) {
            return Map.of("status", "DETERMINISTIC_VERIFIED", "capability_visible", false);
        }
        SchemaFacts schema = schemaFacts(asMap(descriptor.get("inputSchema")));
        if (!schema.verified()) {
            return Map.of("status", "UNRESOLVED", "capability_visible", true);
        }

        List<String> provided = arguments.keySet().stream()
                .map(String::valueOf)
                .filter(SAFE_ARGUMENT_NAMES::contains)
                .sorted()
                .toList();
        List<String> missing = missingArguments(schema.requiredArguments(), arguments);
        return Map.of(
                "status", "DETERMINISTIC_VERIFIED",
                "capability_visible", true,
                "required_arguments_verified", true,
                "required_arguments", schema.requiredArguments(),
                "provided_argument_names", provided,
                "missing_required_arguments", missing,
                "complete", missing.isEmpty());
    }

    private static List<String> fixtureMissingArguments(P3BCorpus.P3BScenario scenario,
                                                        SchemaFacts schema) {
        if (scenario == null || !schema.verified()) return List.of();
        BrainDecision decision = scenario.definition().given().brainScript().decision();
        Map<String, Object> payload = decision.payload();
        if (!"INVOKE_CAPABILITY".equals(decision.actionFamily())
                || !MEMORY_SEARCH_ID.equals(payload.get("capabilityId"))) {
            return List.of();
        }
        return missingArguments(schema.requiredArguments(), asMap(payload.get("arguments")));
    }

    private static List<String> fixtureProvidedArgumentNames(P3BCorpus.P3BScenario scenario) {
        if (scenario == null) return List.of();
        BrainDecision decision = scenario.definition().given().brainScript().decision();
        Map<String, Object> payload = decision.payload();
        if (!"INVOKE_CAPABILITY".equals(decision.actionFamily())
                || !MEMORY_SEARCH_ID.equals(payload.get("capabilityId"))) {
            return List.of();
        }
        return asMap(payload.get("arguments")).keySet().stream()
                .filter(name -> SAFE_ARGUMENT_NAMES.contains(String.valueOf(name)))
                .map(String::valueOf)
                .sorted()
                .toList();
    }

    private static List<String> missingArguments(List<String> required,
                                                 Map<String, Object> arguments) {
        return required.stream().filter(name -> {
            Object value = arguments.get(name);
            return value == null || value instanceof String text && text.isBlank();
        }).toList();
    }

    /** 声明式口径:必填参数中尚未由**模型可见输入**提供的那部分。 */
    private static List<String> missingArguments(List<String> required,
                                                 List<String> providedNames) {
        return required.stream().filter(name -> !providedNames.contains(name)).toList();
    }

    private static Map<String, Object> findMemorySearch(List<?> descriptors) {
        for (Object value : descriptors) {
            Map<String, Object> descriptor = asMap(value);
            if (MEMORY_SEARCH_ID.equals(descriptor.get("id"))) return descriptor;
            // CapabilityDescriptor's Java record serializes capabilityId on some trace versions.
            if (MEMORY_SEARCH_ID.equals(descriptor.get("capabilityId"))) return descriptor;
        }
        return Map.of();
    }

    private static SchemaFacts schemaFacts(Map<String, Object> schema) {
        Map<String, Object> properties = asMap(schema.get("properties"));
        if (properties.isEmpty()) properties = schema;

        Map<String, Object> query = asMap(properties.get("query"));
        boolean queryIsString = "string".equals(query.get("type"));
        LinkedHashSet<String> required = new LinkedHashSet<>();
        boolean recognized = queryIsString;

        if (schema.get("required") instanceof List<?> declaredRequired) {
            for (Object item : declaredRequired) {
                if (!(item instanceof String name) || !SAFE_ARGUMENT_NAMES.contains(name)
                        || !properties.containsKey(name)) {
                    recognized = false;
                    continue;
                }
                required.add(name);
            }
            if (Boolean.TRUE.equals(query.get("required")) && !required.contains("query")) {
                recognized = false;
            }
            for (String name : SAFE_ARGUMENT_NAMES) {
                Map<String, Object> property = asMap(properties.get(name));
                if (Boolean.TRUE.equals(property.get("required")) && !required.contains(name)) {
                    recognized = false;
                }
            }
        } else {
            for (String name : SAFE_ARGUMENT_NAMES) {
                Map<String, Object> property = asMap(properties.get(name));
                if (Boolean.TRUE.equals(property.get("required"))) required.add(name);
            }
            for (Map.Entry<String, Object> entry : properties.entrySet()) {
                if (!SAFE_ARGUMENT_NAMES.contains(entry.getKey())
                        && Boolean.TRUE.equals(asMap(entry.getValue()).get("required"))) {
                    recognized = false;
                }
            }
        }

        if (!required.contains("query")) recognized = false;
        List<String> requiredArguments = required.stream().sorted().toList();
        return new SchemaFacts(recognized, requiredArguments);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static List<?> asList(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }

    private record SchemaFacts(boolean verified, List<String> requiredArguments) {
    }
}
