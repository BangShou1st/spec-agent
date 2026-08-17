package com.specagent.model.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.AgentTaskType;
import com.specagent.agent.ModelContractException;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Strict fail-closed parsing of the four production structured contracts.
 * Every rejected output raises ModelContractException; nothing is repaired,
 * coerced, or retried.
 */
class StructuredModelOutputParserTest {

    private final StructuredModelOutputParser parser =
            new StructuredModelOutputParser(new ObjectMapper());

    private String nodeDraft(String question) {
        return """
                {
                  "question": "%s",
                  "purpose": "clarifies the goal",
                  "options": [{"label": "yes", "impact": "outcome confirmed"}],
                  "allowFreeAnswer": false
                }
                """.formatted(question);
    }

    private String claims(JsonNode... claims) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ArrayNode array = mapper.createArrayNode();
        for (JsonNode claim : claims) {
            array.add(claim);
        }
        return mapper.writeValueAsString(array);
    }

    private JsonNode claim(String kind, String text, String status, Object confidence) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode node = mapper.createObjectNode();
        node.put("kind", kind);
        node.put("text", text);
        node.put("status", status);
        if (confidence instanceof Number number) {
            node.put("confidence", number.doubleValue());
        } else if (confidence instanceof String string) {
            node.put("confidence", string);
        }
        return node;
    }

    @Test
    void parsesValidNodeDraft() {
        JsonNode parsed = parser.parse(AgentTaskType.DRAFT_NODE, nodeDraft("What outcome matters most?"));

        assertThat(parsed.get("question").asText()).isEqualTo("What outcome matters most?");
        assertThat(parsed.get("options")).hasSize(1);
        assertThat(parsed.get("options").get(0).has("id")).isFalse();
        assertThat(parsed.get("allowFreeAnswer").asBoolean()).isFalse();
    }

    @Test
    void parsesValidNodeDraftWithEmptyOptionsAndFreeAnswer() {
        JsonNode parsed = parser.parse(AgentTaskType.DRAFT_NODE, """
                {
                  "question": "What is missing?",
                  "purpose": "",
                  "options": [],
                  "allowFreeAnswer": true
                }
                """);

        assertThat(parsed.get("options")).isEmpty();
        assertThat(parsed.get("allowFreeAnswer").asBoolean()).isTrue();
    }

    @Test
    void parsesValidInterpretation() {
        JsonNode parsed = parser.parse(AgentTaskType.INTERPRET_ANSWER, """
                {
                  "confirmedTexts": ["the main outcome is clear"],
                  "assumedTexts": [],
                  "unresolvedTexts": ["scope is open"],
                  "conflictTexts": []
                }
                """);

        assertThat(parsed.get("confirmedTexts")).hasSize(1);
        assertThat(parsed.get("assumedTexts")).isEmpty();
    }

    @Test
    void parsesValidAnswerPatchDraft() throws Exception {
        String output = "{\"claims\":" + claims(
                claim("goal", "the outcome is specified", "confirmed", 0.9),
                claim("open_question", "scope must be confirmed", "unresolved", 0.5)) + "}";

        JsonNode parsed = parser.parse(AgentTaskType.DRAFT_ANSWER_PATCH, output);

        assertThat(parsed.get("claims")).hasSize(2);
        assertThat(parsed.get("claims").get(0).get("confidence").asDouble()).isEqualTo(0.9);
    }

    @Test
    void parsesValidSpecDraft() {
        String snapshotId = UUID.randomUUID().toString();
        JsonNode parsed = parser.parse(AgentTaskType.DRAFT_SPEC, """
                {
                  "sections": {"Overview": "the clarified outcome"},
                  "unresolvedItems": ["confirm scope before finalization"],
                  "sourceRefsBySection": {"Overview": ["context:%s"]}
                }
                """.formatted(snapshotId));

        assertThat(parsed.get("sections").get("Overview").asText()).isNotBlank();
        assertThat(parsed.get("sourceRefsBySection").get("Overview")).hasSize(1);
    }

    @Test
    void parsesSpecDraftWithNodeAndAnswerRefs() {
        String nodeId = UUID.randomUUID().toString();
        String answerId = UUID.randomUUID().toString();
        parser.parse(AgentTaskType.DRAFT_SPEC, """
                {
                  "sections": {"Overview": "content"},
                  "unresolvedItems": [],
                  "sourceRefsBySection": {"Overview": ["node:%s", "answer:%s", "route:%s", "patch:%s"]}
                }
                """.formatted(nodeId, answerId, UUID.randomUUID(), UUID.randomUUID()));
    }

    @Test
    void rejectsNonJsonOutput() {
        assertThatThrownBy(() -> parser.parse(AgentTaskType.DRAFT_NODE, "this is not json"))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void rejectsNonObjectOutput() {
        assertThatThrownBy(() -> parser.parse(AgentTaskType.DRAFT_NODE, "[1,2,3]"))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("JSON object");
    }

    @Test
    void rejectsNodeDraftMissingQuestion() {
        assertThatThrownBy(() -> parser.parse(AgentTaskType.DRAFT_NODE, """
                {"purpose": "p", "options": [], "allowFreeAnswer": true}
                """))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("question");
    }

    @Test
    void rejectsBlankQuestion() {
        assertThatThrownBy(() -> parser.parse(AgentTaskType.DRAFT_NODE,
                nodeDraft("   ")))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("question");
    }

    @Test
    void rejectsNodeOptionWithRuntimeOwnedId() {
        assertThatThrownBy(() -> parser.parse(AgentTaskType.DRAFT_NODE, """
                {
                  "question": "pick one",
                  "purpose": "p",
                  "options": [{"id": "%s", "label": "a", "impact": "b"}],
                  "allowFreeAnswer": false
                }
                """.formatted(UUID.randomUUID())))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("runtime-owned field")
                .hasMessageContaining("option.id");
    }

    @Test
    void rejectsOptionsAsNonArray() {
        assertThatThrownBy(() -> parser.parse(AgentTaskType.DRAFT_NODE, """
                {"question": "q", "purpose": "p", "options": {}, "allowFreeAnswer": true}
                """))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("options");
    }

    @Test
    void rejectsAllowFreeAnswerAsNonBoolean() {
        assertThatThrownBy(() -> parser.parse(AgentTaskType.DRAFT_NODE, """
                {"question": "q", "purpose": "p", "options": [], "allowFreeAnswer": "yes"}
                """))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("allowFreeAnswer");
    }

    @Test
    void rejectsInterpretationMissingField() {
        assertThatThrownBy(() -> parser.parse(AgentTaskType.INTERPRET_ANSWER, """
                {"confirmedTexts": ["a"], "assumedTexts": [], "unresolvedTexts": []}
                """))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("conflictTexts");
    }

    @Test
    void rejectsBlankInterpretationElement() {
        assertThatThrownBy(() -> parser.parse(AgentTaskType.INTERPRET_ANSWER, """
                {"confirmedTexts": ["  "], "assumedTexts": [], "unresolvedTexts": [], "conflictTexts": []}
                """))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("confirmedTexts");
    }

    @Test
    void rejectsWrongTypeForTextsField() {
        assertThatThrownBy(() -> parser.parse(AgentTaskType.INTERPRET_ANSWER, """
                {"confirmedTexts": "not an array", "assumedTexts": [], "unresolvedTexts": [], "conflictTexts": []}
                """))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("confirmedTexts");
    }

    @Test
    void rejectsClaimWithRuntimeOwnedId() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode claimWithId = mapper.createObjectNode();
        claimWithId.put("id", UUID.randomUUID().toString());
        claimWithId.put("kind", "goal");
        claimWithId.put("text", "t");
        claimWithId.put("status", "confirmed");
        claimWithId.put("confidence", 0.9);
        String output = "{\"claims\":" + claims(claimWithId) + "}";

        assertThatThrownBy(() -> parser.parse(AgentTaskType.DRAFT_ANSWER_PATCH, output))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("claim.id");
    }

    @Test
    void rejectsClaimWithRuntimeOwnedSourceIds() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode claimWithSources = mapper.createObjectNode();
        claimWithSources.put("kind", "goal");
        claimWithSources.put("text", "t");
        claimWithSources.put("status", "confirmed");
        claimWithSources.put("confidence", 0.9);
        claimWithSources.put("sourceNodeId", UUID.randomUUID().toString());
        claimWithSources.put("sourceAnswerId", UUID.randomUUID().toString());
        String output = "{\"claims\":" + claims(claimWithSources) + "}";

        assertThatThrownBy(() -> parser.parse(AgentTaskType.DRAFT_ANSWER_PATCH, output))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("sourceNodeId");
    }

    @Test
    void rejectsUnknownClaimKind() throws Exception {
        String output = "{\"claims\":" + claims(
                claim("feature", "a feature idea", "confirmed", 0.9)) + "}";

        assertThatThrownBy(() -> parser.parse(AgentTaskType.DRAFT_ANSWER_PATCH, output))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("unknown kind")
                .hasMessageContaining("feature");
    }

    @Test
    void rejectsUnknownClaimStatus() throws Exception {
        String output = "{\"claims\":" + claims(
                claim("goal", "a goal", "definitely", 0.9)) + "}";

        assertThatThrownBy(() -> parser.parse(AgentTaskType.DRAFT_ANSWER_PATCH, output))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("unknown status");
    }

    @Test
    void rejectsConfidenceAboveOne() throws Exception {
        String output = "{\"claims\":" + claims(
                claim("goal", "a goal", "confirmed", 2.7)) + "}";

        assertThatThrownBy(() -> parser.parse(AgentTaskType.DRAFT_ANSWER_PATCH, output))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    void rejectsNegativeConfidence() throws Exception {
        String output = "{\"claims\":" + claims(
                claim("goal", "a goal", "confirmed", -1)) + "}";

        assertThatThrownBy(() -> parser.parse(AgentTaskType.DRAFT_ANSWER_PATCH, output))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    void rejectsConfidenceAsString() throws Exception {
        String output = "{\"claims\":" + claims(
                claim("goal", "a goal", "confirmed", "high")) + "}";

        assertThatThrownBy(() -> parser.parse(AgentTaskType.DRAFT_ANSWER_PATCH, output))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    void rejectsMissingConfidence() throws Exception {
        String output = "{\"claims\":" + claims(
                claim("goal", "a goal", "confirmed", null)) + "}";

        assertThatThrownBy(() -> parser.parse(AgentTaskType.DRAFT_ANSWER_PATCH, output))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("confidence");
    }

    @Test
    void rejectsBlankClaimText() throws Exception {
        String output = "{\"claims\":" + claims(
                claim("goal", "   ", "confirmed", 0.9)) + "}";

        assertThatThrownBy(() -> parser.parse(AgentTaskType.DRAFT_ANSWER_PATCH, output))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("text");
    }

    @Test
    void rejectsBlankSpecSectionContent() {
        assertThatThrownBy(() -> parser.parse(AgentTaskType.DRAFT_SPEC, """
                {
                  "sections": {"Overview": "  "},
                  "unresolvedItems": [],
                  "sourceRefsBySection": {"Overview": ["context:%s"]}
                }
                """.formatted(UUID.randomUUID())))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("section content");
    }

    @Test
    void rejectsSpecSectionWithoutSourceRefs() {
        assertThatThrownBy(() -> parser.parse(AgentTaskType.DRAFT_SPEC, """
                {
                  "sections": {"Overview": "content"},
                  "unresolvedItems": [],
                  "sourceRefsBySection": {"Overview": "context:%s"}
                }
                """.formatted(UUID.randomUUID())))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("source references must be an array");
    }

    @Test
    void rejectsMalformedSourceRefWithoutColon() {
        assertThatThrownBy(() -> parser.parse(AgentTaskType.DRAFT_SPEC, """
                {
                  "sections": {"Overview": "content"},
                  "unresolvedItems": [],
                  "sourceRefsBySection": {"Overview": ["context%s"]}
                }
                """.formatted(UUID.randomUUID())))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("malformed source reference");
    }

    @Test
    void rejectsUnknownSourceRefKind() {
        assertThatThrownBy(() -> parser.parse(AgentTaskType.DRAFT_SPEC, """
                {
                  "sections": {"Overview": "content"},
                  "unresolvedItems": [],
                  "sourceRefsBySection": {"Overview": ["magic:%s"]}
                }
                """.formatted(UUID.randomUUID())))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("unknown source reference kind");
    }

    @Test
    void rejectsSourceRefWithInvalidUuid() {
        assertThatThrownBy(() -> parser.parse(AgentTaskType.DRAFT_SPEC, """
                {
                  "sections": {"Overview": "content"},
                  "unresolvedItems": [],
                  "sourceRefsBySection": {"Overview": ["context:not-a-uuid"]}
                }
                """))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("malformed source reference");
    }

    @Test
    void failsClosedForUnsupportedTask() {
        assertThatThrownBy(() -> parser.parse(AgentTaskType.GAP_ANALYSIS, "{\"a\":1}"))
                .isInstanceOf(ModelContractException.class)
                .hasMessageContaining("No structured contract")
                .hasMessageContaining("gap_analysis");
    }
}