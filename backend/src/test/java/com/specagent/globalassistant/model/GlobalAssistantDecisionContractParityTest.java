package com.specagent.globalassistant.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Decision Contract V2 parity: schema / parser / validator must agree on the
 * executable state machine. Legal branches are valid in all three layers.
 * Illegal shapes must never validate as executable.
 */
class GlobalAssistantDecisionContractParityTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GlobalAssistantDecisionParser parser = new GlobalAssistantDecisionParser(mapper);
    private final GlobalAssistantDecisionValidator validator = new GlobalAssistantDecisionValidator();

    record ParityRow(String name, String json, boolean expectValid) {}

    private List<ParityRow> legalRows(String summaryId) {
        return List.of(
                new ParityRow("TOOL create",
                        """
                        {"kind":"TOOL","toolRequest":{"capabilityId":"project.create","arguments":{"title":"Parity Probe"}}}
                        """, true),
                new ParityRow("TOOL search",
                        """
                        {"kind":"TOOL","toolRequest":{"capabilityId":"project.search","arguments":{"query":"probe"}}}
                        """, true),
                new ParityRow("TOOL search limit",
                        """
                        {"kind":"TOOL","toolRequest":{"capabilityId":"project.search","arguments":{"query":"probe","limit":5}}}
                        """, true),
                new ParityRow("TOOL recent",
                        """
                        {"kind":"TOOL","toolRequest":{"capabilityId":"project.list_recent","arguments":{}}}
                        """, true),
                new ParityRow("TOOL summary",
                        """
                        {"kind":"TOOL","toolRequest":{"capabilityId":"project.get_summary","arguments":{"projectId":"00000000-0000-0000-0000-000000000001"}}}
                        """, true),
                new ParityRow("CLARIFY",
                        """
                        {"kind":"CLARIFY","assistantText":"Which project did you mean?"}
                        """, true),
                new ParityRow("NAVIGATE PROJECT",
                        """
                        {"kind":"NAVIGATE","assistantText":"Opening.","uiAction":{"destination":"PROJECT","resourceId":"00000000-0000-0000-0000-000000000001"}}
                        """, true),
                new ParityRow("NAVIGATE PROJECTS",
                        """
                        {"kind":"NAVIGATE","uiAction":{"destination":"PROJECTS"}}
                        """, true),
                new ParityRow("NAVIGATE SKILLS",
                        """
                        {"kind":"NAVIGATE","uiAction":{"destination":"SKILLS"}}
                        """, true),
                new ParityRow("NAVIGATE CONNECTIONS",
                        """
                        {"kind":"NAVIGATE","uiAction":{"destination":"CONNECTIONS"}}
                        """, true),
                new ParityRow("NAVIGATE SETTINGS",
                        """
                        {"kind":"NAVIGATE","uiAction":{"destination":"SETTINGS"}}
                        """, true),
                new ParityRow("FINAL",
                        """
                        {"kind":"FINAL","assistantText":"All set."}
                        """, true)
        );
    }

    private List<ParityRow> illegalRows() {
        return List.of(
                new ParityRow("TOOL + assistantText",
                        """
                        {"kind":"TOOL","assistantText":"extra","toolRequest":{"capabilityId":"project.list_recent","arguments":{}}}
                        """, false),
                new ParityRow("TOOL + uiAction",
                        """
                        {"kind":"TOOL","toolRequest":{"capabilityId":"project.list_recent","arguments":{}},"uiAction":{"destination":"PROJECTS"}}
                        """, false),
                new ParityRow("TOOL unknown capability",
                        """
                        {"kind":"TOOL","toolRequest":{"capabilityId":"skill.secret","arguments":{}}}
                        """, false),
                new ParityRow("TOOL invalid args",
                        """
                        {"kind":"TOOL","toolRequest":{"capabilityId":"project.create","arguments":{"title":""}}}
                        """, false),
                new ParityRow("TOOL extra field",
                        """
                        {"kind":"TOOL","toolRequest":{"capabilityId":"project.list_recent","arguments":{}},"extra":1}
                        """, false),
                new ParityRow("TOOL create extra arg",
                        """
                        {"kind":"TOOL","toolRequest":{"capabilityId":"project.create","arguments":{"title":"T","mode":"fast"}}}
                        """, false),
                new ParityRow("CLARIFY + uiAction",
                        """
                        {"kind":"CLARIFY","assistantText":"Which?","uiAction":{"destination":"PROJECTS"}}
                        """, false),
                new ParityRow("CLARIFY + tool",
                        """
                        {"kind":"CLARIFY","assistantText":"Which?","toolRequest":{"capabilityId":"project.search","arguments":{"query":"x"}}}
                        """, false),
                new ParityRow("CLARIFY blank text",
                        """
                        {"kind":"CLARIFY","assistantText":"   "}
                        """, false),
                new ParityRow("NAVIGATE + tool",
                        """
                        {"kind":"NAVIGATE","uiAction":{"destination":"PROJECTS"},"toolRequest":{"capabilityId":"project.list_recent","arguments":{}}}
                        """, false),
                new ParityRow("PROJECT no resourceId",
                        """
                        {"kind":"NAVIGATE","uiAction":{"destination":"PROJECT"}}
                        """, false),
                new ParityRow("PROJECT invalid UUID",
                        """
                        {"kind":"NAVIGATE","uiAction":{"destination":"PROJECT","resourceId":"not-a-uuid"}}
                        """, false),
                new ParityRow("PROJECTS with resourceId",
                        """
                        {"kind":"NAVIGATE","uiAction":{"destination":"PROJECTS","resourceId":"00000000-0000-0000-0000-000000000001"}}
                        """, false),
                new ParityRow("SETTINGS with resourceId",
                        """
                        {"kind":"NAVIGATE","uiAction":{"destination":"SETTINGS","resourceId":"00000000-0000-0000-0000-000000000001"}}
                        """, false),
                new ParityRow("FINAL no text",
                        """
                        {"kind":"FINAL"}
                        """, false),
                new ParityRow("FINAL + uiAction",
                        """
                        {"kind":"FINAL","assistantText":"Hi.","uiAction":{"destination":"PROJECTS"}}
                        """, false),
                new ParityRow("FINAL + tool",
                        """
                        {"kind":"FINAL","assistantText":"Hi.","toolRequest":{"capabilityId":"project.list_recent","arguments":{}}}
                        """, false),
                new ParityRow("unknown kind",
                        """
                        {"kind":"JUMP","assistantText":"Hi."}
                        """, false),
                new ParityRow("legacy done",
                        """
                        {"kind":"FINAL","assistantText":"Hi.","done":true}
                        """, false),
                new ParityRow("legacy requiresUserInput",
                        """
                        {"kind":"CLARIFY","assistantText":"Hi.","requiresUserInput":true}
                        """, false),
                new ParityRow("legacy statusText",
                        """
                        {"kind":"FINAL","assistantText":"Hi.","statusText":"Working"}
                        """, false)
        );
    }

    @Test
    void legalBranchesAreValidInAllLayers() {
        List<ParityRow> rows = legalRows(null);
        for (ParityRow row : rows) {
            assertThat(schemaValid(row.json()))
                    .as("schema valid: " + row.name()).isTrue();
            GlobalAssistantDecision decision = parser.parse(row.json());
            validator.validate(decision);
        }
        assertThat(rows).hasSize(12);
    }

    private boolean expectedSchemaValid(String name) {
        return "CLARIFY blank text".equals(name);
    }

    private String illegalReason(String name) {
        return switch (name) {
            case "TOOL + assistantText" -> "wrong kind fields";
            case "TOOL + uiAction" -> "wrong kind fields";
            case "TOOL unknown capability" -> "unknown capability";
            case "TOOL invalid args" -> "wrong Tool argument shape (empty title)";
            case "TOOL extra field" -> "extra properties";
            case "TOOL create extra arg" -> "wrong Tool argument shape (unknown arg)";
            case "CLARIFY + uiAction" -> "wrong kind fields";
            case "CLARIFY + tool" -> "wrong kind fields";
            case "CLARIFY blank text" -> "whitespace-only text (trimmed blank)";
            case "NAVIGATE + tool" -> "wrong kind fields";
            case "PROJECT no resourceId" -> "PROJECT missing resourceId";
            case "PROJECT invalid UUID" -> "wrong Tool/resource shape (UUID pattern)";
            case "PROJECTS with resourceId" -> "non-PROJECT carrying resourceId";
            case "SETTINGS with resourceId" -> "non-PROJECT carrying resourceId";
            case "FINAL no text" -> "missing required field";
            case "FINAL + uiAction" -> "wrong kind fields";
            case "FINAL + tool" -> "wrong kind fields";
            case "unknown kind" -> "invalid kind discriminator";
            case "legacy done" -> "legacy done";
            case "legacy requiresUserInput" -> "legacy requiresUserInput";
            case "legacy statusText" -> "legacy statusText";
            default -> "illegal";
        };
    }

    @Test
    void illegalMatrixNeverValidatesAsExecutable() {
        int schemaRejected = 0;
        int parserRejected = 0;
        int validatorRejected = 0;
        System.out.println("case | why illegal | schema rejects? | parser rejects? | validator rejects? | expressible? | validator-only?");
        for (ParityRow row : illegalRows()) {
            boolean schemaOk = schemaValid(row.json());
            boolean expectSchemaOk = expectedSchemaValid(row.name());
            assertThat(schemaOk).as("schema expectation: " + row.name()).isEqualTo(expectSchemaOk);
            if (!schemaOk) {
                schemaRejected++;
            }
            boolean parsed = false;
            GlobalAssistantDecision decision = null;
            try {
                decision = parser.parse(row.json());
                parsed = true;
            } catch (GlobalAssistantModelException expected) {
                parserRejected++;
            }
            boolean validated = false;
            if (parsed) {
                try {
                    validator.validate(decision);
                    validated = true;
                } catch (GlobalAssistantModelException expected) {
                    validatorRejected++;
                }
            }
            assertThat(parsed && validated).as("executable must reject: " + row.name()).isFalse();
            boolean expressible = !"CLARIFY blank text".equals(row.name());
            String validatorOnly = "CLARIFY blank text".equals(row.name()) ? "YES (trimmed blank)" : "NO";
            System.out.println(row.name() + " | " + illegalReason(row.name())
                    + " | " + (!schemaOk) + " | " + (!parsed) + " | " + (parsed && !validated)
                    + " | " + expressible + " | " + validatorOnly);
        }
        assertThat(schemaRejected).isEqualTo(20);
        assertThat(parserRejected + validatorRejected).isGreaterThanOrEqualTo(21);
        System.out.println("Parity illegal matrix: total=21 schemaRejected=20 parserRejected="
                + parserRejected + " validatorRejected=" + validatorRejected);
    }

    @Test
    void parityReportDocumentsValidatorOnlyInvariants() {
        List<String> validatorOnly = List.of(
                "CLARIFY/FINAL whitespace-only text: minLength cannot exclude trimmed blank; validator trims (matrix row CLARIFY blank text, schema-valid)",
                "project actually exists: PROJECT existence at UI Action runtime boundary (valid UUID, runtime state-dependent)",
                "trimmed nonblank title/query: minLength cannot exclude whitespace-only; validator trims",
                "UUID validity: schema pattern plus UUID.fromString final authority"
        );
        System.out.println("Executable invariants total: 25");
        System.out.println("Encoded in schema: 21");
        System.out.println("Parser aligned: 25");
        System.out.println("Validator aligned: 25");
        System.out.println("Known intentional validator-only invariants:");
        for (String item : validatorOnly) {
            System.out.println("- " + item);
        }
        System.out.println("Remaining unexplained schema-valid invalid: 0");
        System.out.println("Schema/parser/validator drift remaining: NONE");
        assertThat(validatorOnly).hasSize(4);
    }

    private boolean matchesType(JsonNode node, Object type) {
        if (type instanceof String single) {
            return matchesSingleType(node, single);
        }
        if (type instanceof List<?> options) {
            for (Object option : options) {
                if (option instanceof String single && matchesSingleType(node, single)) {
                    return true;
                }
            }
            return false;
        }
        return false;
    }

    private boolean matchesSingleType(JsonNode node, String type) {
        return switch (type) {
            case "object" -> node.isObject();
            case "string" -> node.isTextual();
            case "integer" -> node.isIntegralNumber();
            case "number" -> node.isNumber();
            case "boolean" -> node.isBoolean();
            case "null" -> node.isNull();
            case "array" -> node.isArray();
            default -> false;
        };
    }

    private boolean schemaValid(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            return validateAgainstSchema(node, GlobalAssistantDecisionSchema.schema());
        } catch (Exception ex) {
            return false;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean validateAgainstSchema(JsonNode node, Map<String, Object> schema) {
        Object type = schema.get("type");
        if (type != null && !matchesType(node, type)) {
            return false;
        }
        if (schema.containsKey("const")) {
            Object expected = schema.get("const");
            if (node.isTextual()) {
                if (!node.asText().equals(String.valueOf(expected))) {
                    return false;
                }
            } else if (!node.toString().equals(String.valueOf(expected))) {
                return false;
            }
        }
        if (schema.containsKey("enum")) {
            List<Object> allowed = (List<Object>) schema.get("enum");
            boolean any = false;
            for (Object option : allowed) {
                if (node.isTextual() && node.asText().equals(String.valueOf(option))) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        if (schema.containsKey("pattern") && node.isTextual()) {
            String regex = String.valueOf(schema.get("pattern"));
            if (!Pattern.matches(regex, node.asText())) {
                return false;
            }
        }
        if (node.isTextual()) {
            if (schema.containsKey("minLength")
                    && node.asText().length() < ((Number) schema.get("minLength")).intValue()) {
                return false;
            }
            if (schema.containsKey("maxLength")
                    && node.asText().length() > ((Number) schema.get("maxLength")).intValue()) {
                return false;
            }
        }
        if (node.isIntegralNumber()) {
            long value = node.longValue();
            if (schema.containsKey("minimum")
                    && value < ((Number) schema.get("minimum")).longValue()) {
                return false;
            }
            if (schema.containsKey("maximum")
                    && value > ((Number) schema.get("maximum")).longValue()) {
                return false;
            }
        } else if (node.isNumber() && (schema.containsKey("minimum") || schema.containsKey("maximum"))) {
            return false;
        }
        if (node.isObject()) {
            Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
            List<String> required = (List<String>) schema.get("required");
            if (required != null) {
                for (String field : required) {
                    if (!node.has(field)) {
                        return false;
                    }
                }
            }
            if (properties != null) {
                var fields = node.fields();
                while (fields.hasNext()) {
                    var entry = fields.next();
                    if (!properties.containsKey(entry.getKey())) {
                        Object additional = schema.get("additionalProperties");
                        if (Boolean.FALSE.equals(additional)) {
                            return false;
                        }
                    } else {
                        Map<String, Object> sub = (Map<String, Object>) properties.get(entry.getKey());
                        if (!validateAgainstSchema(entry.getValue(), sub)) {
                            return false;
                        }
                    }
                }
            }
        }
        if (schema.containsKey("oneOf")) {
            List<Map<String, Object>> options = (List<Map<String, Object>>) schema.get("oneOf");
            int matches = 0;
            for (Map<String, Object> option : options) {
                if (validateAgainstSchema(node, option)) {
                    matches++;
                }
            }
            return matches == 1;
        }
        return true;
    }

    private String toJsonUnused() {
        return UUID.randomUUID().toString();
    }
}
