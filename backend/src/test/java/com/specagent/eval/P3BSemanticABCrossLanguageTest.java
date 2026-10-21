package com.specagent.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.MemorySearchCapability;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Cross-language regression for Java's safe C09 facts and the Python scorer. */
class P3BSemanticABCrossLanguageTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void javaFrozenCapabilityFactsReachPythonWithoutLosingMemorySearchOrQuery() throws Exception {
        Path root = projectRoot();
        P3BCorpus.P3BScenario c09 = P3BCorpus.all().stream()
                .filter(scenario -> "P3B-C09".equals(scenario.scenarioId()))
                .findFirst().orElseThrow();
        CapabilityDescriptor memorySearch = new MemorySearchCapability(null).descriptor();
        List<Map<String, Object>> descriptors = List.of(
                Map.of("id", memorySearch.capabilityId(),
                        "description", memorySearch.description(),
                        "inputSchema", memorySearch.inputSchema()),
                Map.of("id", "private.internal.capability",
                        "description", "PRIVATE_DESCRIPTION_MUST_NOT_BE_EXPORTED",
                        "inputSchema", Map.of("privateArgument", Map.of("required", true))));

        Map<String, Object> facts = P3BSafeCapabilityEvidence.summarize(descriptors, c09);
        assertThat(facts.get("memory_search_visible")).isEqualTo(true);
        assertThat(facts.get("memory_search_query_required")).isEqualTo(true);
        assertThat(facts.get("memory_search_missing_arguments")).isEqualTo(List.of("query"));
        assertThat(facts.get("memory_search_provided_argument_names")).isEqualTo(List.of("scope"));
        assertThat(facts.get("required_arguments_verified")).isEqualTo(true);
        Map<?, ?> memorySearchFacts =
                (Map<?, ?>) ((List<?>) facts.get("available_capabilities")).get(0);
        assertThat(memorySearchFacts.get("id")).isEqualTo("memory.search");
        assertThat(((List<?>) memorySearchFacts.get("required_arguments")).stream()
                .anyMatch("query"::equals)).isTrue();
        String factsJson = mapper.writeValueAsString(facts);
        assertThat(factsJson).contains("memory.search", "query")
                .doesNotContain("PRIVATE_DESCRIPTION_MUST_NOT_BE_EXPORTED", "privateArgument");

        Map<String, Object> attempt = new LinkedHashMap<>();
        attempt.put("scenario_id", "P3B-C09");
        attempt.put("model_selected_action", "REQUEST_USER_INPUT");
        attempt.put("oracle_candidate", Map.of(
                "acceptable_primary_actions", List.of("REQUEST_USER_INPUT")));
        attempt.put("input_evidence", facts);
        attempt.put("model_raw_action", Map.of("payload", Map.of(
                "questionText", "请问需要在当前项目范围内检索哪些关键词？")));
        attempt.put("automatic_semantic_review", Map.of(
                "clarification_structure_audit", Map.of(
                        "status", "DETERMINISTIC_VERIFIED",
                        "question_text_present", true,
                        "semantic_intent", "UNRESOLVED")));
        attempt.put("capability_calls", 0);
        attempt.put("execution_state", "completed");

        Path input = Files.createTempFile("p3b-c09-cross-language-", ".json");
        try {
            Files.writeString(input, mapper.writeValueAsString(attempt));
            String python = pythonExecutable(root);
            String bridge = "import json,sys; sys.path.insert(0,sys.argv[1]); "
                    + "from p3b_semantic_ab import _assess_c09_attempt; "
                    + "print(json.dumps(_assess_c09_attempt(json.load(open(sys.argv[2],encoding='utf-8')))))";
            Process process = new ProcessBuilder(python, "-c", bridge,
                    root.resolve("tools/p3b").toString(), input.toString())
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8).trim();
            int exitCode = process.waitFor();
            assertThat(exitCode).as("Python scorer exit code; output contains diagnostics only")
                    .withFailMessage("Python scorer exited %s: %s", exitCode, output)
                    .isZero();
            Map<?, ?> assessment = mapper.readValue(output, Map.class);
            assertThat(assessment.get("status")).isEqualTo("CORRECT");
            assertThat(assessment.get("structure_status")).isEqualTo("PASS");
            assertThat(assessment.get("semantic_intent")).isEqualTo("PASS");
            assertThat(((Map<?, ?>) assessment.get("semantic_review")).get("reviewer"))
                    .isEqualTo("p3b-c09-rule-judge.v1");
        } finally {
            Files.deleteIfExists(input);
        }
    }

    private static Path projectRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (current != null
                && !Files.isRegularFile(current.resolve("tools/p3b/p3b_semantic_ab.py"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("Project root not found");
        return current;
    }

    private static String pythonExecutable(Path root) {
        String configured = System.getenv("SPEC_AGENT_P3B_EVAL_PYTHON");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("SPEC_AGENT_GA_TEST_PYTHON");
        }
        if (configured != null && !configured.isBlank()) {
            Path path = Path.of(configured);
            Path rootRelative = path.isAbsolute() ? path : root.resolve(path).normalize();
            if (Files.isRegularFile(rootRelative)) return rootRelative.toString();
            Path cwdRelative = path.isAbsolute() ? path
                    : Path.of(System.getProperty("user.dir")).resolve(path).normalize();
            if (Files.isRegularFile(cwdRelative)) return cwdRelative.toString();
            return configured;
        }
        Path defaultPython = root.resolve("agent-brain/.venv/Scripts/python.exe");
        return Files.isRegularFile(defaultPython) ? defaultPython.toString() : "python";
    }
}
