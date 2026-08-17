package com.specagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.contracts.AnswerInterpretationResult;
import com.specagent.agent.contracts.AnswerPatchDraft;
import com.specagent.agent.contracts.NodeDraft;
import com.specagent.agent.contracts.SpecDraft;
import com.specagent.node.NodeOption;
import com.specagent.patch.Claim;
import com.specagent.patch.ClaimKind;
import com.specagent.patch.ClaimStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runtime ID and provenance mapping: the model proposes content, the runtime
 * assigns every identity. Mapped drafts must never carry ids or sources the
 * model could have chosen.
 */
class StructuredOutputMapperTest {

    private final StructuredOutputMapper mapper = new StructuredOutputMapper();
    private final ObjectMapper jsonMapper = new ObjectMapper();

    @Test
    void mapsNodeDraftWithRuntimeOwnedOptionIds() {
        JsonNode node = jsonMapper.valueToTree(Map.of(
                "question", "What outcome matters?",
                "purpose", "clarifies the goal",
                "options", List.of(Map.of("label", "yes", "impact", "confirmed")),
                "allowFreeAnswer", false));

        NodeDraft draft = mapper.toNodeDraft(node);

        assertThat(draft.question()).isEqualTo("What outcome matters?");
        assertThat(draft.options()).hasSize(1);
        NodeOption option = draft.options().get(0);
        assertThat(option.label()).isEqualTo("yes");
        assertThat(option.impact()).isEqualTo("confirmed");
        // The model never picks an option id; the runtime assigns it.
        assertThat(option.id()).isNotNull();
    }

    @Test
    void mapsInterpretationLists() {
        JsonNode node = jsonMapper.valueToTree(Map.of(
                "confirmedTexts", List.of("a"),
                "assumedTexts", List.of("b"),
                "unresolvedTexts", List.of("c"),
                "conflictTexts", List.of("d")));

        AnswerInterpretationResult result = mapper.toInterpretation(node);

        assertThat(result.confirmedTexts()).containsExactly("a");
        assertThat(result.assumedTexts()).containsExactly("b");
        assertThat(result.unresolvedTexts()).containsExactly("c");
        assertThat(result.conflictTexts()).containsExactly("d");
    }

    @Test
    void mapsPatchDraftWithRuntimeOwnedClaimIdsAndNoModelSources() {
        JsonNode node = jsonMapper.valueToTree(Map.of(
                "claims", List.of(
                        Map.of("kind", "goal", "text", "the outcome",
                                "status", "confirmed", "confidence", 0.9),
                        Map.of("kind", "open_question", "text", "scope open",
                                "status", "unresolved", "confidence", 0.4))));

        AnswerPatchDraft draft = mapper.toPatchDraft(node);

        assertThat(draft.claims()).hasSize(2);
        Claim confirmed = draft.claims().get(0);
        assertThat(confirmed.kind()).isEqualTo(ClaimKind.GOAL);
        assertThat(confirmed.status()).isEqualTo(ClaimStatus.CONFIRMED);
        assertThat(confirmed.confidence()).isEqualTo(0.9);
        // Runtime assigns the id; provenance stays ungrounded for the
        // orchestrator to fill with real answered node and answer ids.
        assertThat(confirmed.id()).isNotNull();
        assertThat(confirmed.sourceNodeId()).isNull();
        assertThat(confirmed.sourceAnswerId()).isNull();
        assertThat(draft.claims().get(1).status()).isEqualTo(ClaimStatus.UNRESOLVED);
    }

    @Test
    void mapsSpecDraftSectionsAndRefs() {
        JsonNode node = jsonMapper.valueToTree(Map.of(
                "sections", Map.of("Overview", "content"),
                "unresolvedItems", List.of("confirm scope"),
                "sourceRefsBySection", Map.of("Overview", List.of("context:x"))));

        SpecDraft draft = mapper.toSpecDraft(node);

        assertThat(draft.sections().get("Overview")).isEqualTo("content");
        assertThat(draft.unresolvedItems()).containsExactly("confirm scope");
        assertThat(draft.sourceRefsBySection().get("Overview")).containsExactly("context:x");
    }
}