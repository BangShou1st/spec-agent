package com.specagent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.specagent.agent.contracts.AnswerInterpretationResult;
import com.specagent.agent.contracts.AnswerPatchDraft;
import com.specagent.agent.contracts.NodeDraft;
import com.specagent.agent.contracts.SpecDraft;
import com.specagent.node.NodeOption;
import com.specagent.patch.Claim;
import com.specagent.patch.ClaimKind;
import com.specagent.patch.ClaimStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps validated model output onto the domain draft contracts.
 *
 * <p>This is the runtime ID and provenance mapping step: the model may only
 * propose content (labels, impacts, claim kinds and texts). The runtime assigns
 * every identity here ({@link NodeOption#of}, {@link Claim#unsourced}) and
 * leaves provenance null for the orchestrator to ground with real answered
 * node and answer ids. The parser has already rejected any runtime-owned
 * identity field the model tried to output.
 */
@Component
public class StructuredOutputMapper {

    public NodeDraft toNodeDraft(JsonNode node) {
        List<NodeOption> options = new ArrayList<>();
        for (JsonNode option : node.get("options")) {
            options.add(NodeOption.of(option.get("label").asText(), option.get("impact").asText()));
        }
        return new NodeDraft(
                node.get("question").asText(),
                node.hasNonNull("purpose") ? node.get("purpose").asText() : "",
                options,
                node.get("allowFreeAnswer").asBoolean());
    }

    public AnswerInterpretationResult toInterpretation(JsonNode node) {
        return new AnswerInterpretationResult(
                toTextList(node.get("confirmedTexts")),
                toTextList(node.get("assumedTexts")),
                toTextList(node.get("unresolvedTexts")),
                toTextList(node.get("conflictTexts")));
    }

    public AnswerPatchDraft toPatchDraft(JsonNode node) {
        List<Claim> claims = new ArrayList<>();
        for (JsonNode claimNode : node.get("claims")) {
            ClaimKind kind = ClaimKind.fromCode(claimNode.get("kind").asText());
            ClaimStatus status = ClaimStatus.fromCode(claimNode.get("status").asText());
            Double confidence = claimNode.get("confidence").isNull()
                    ? null : claimNode.get("confidence").doubleValue();
            claims.add(Claim.unsourced(kind, claimNode.get("text").asText(), status, confidence));
        }
        return new AnswerPatchDraft(claims);
    }

    public SpecDraft toSpecDraft(JsonNode node) {
        Map<String, String> sections = new LinkedHashMap<>();
        node.get("sections").properties().forEach(
                entry -> sections.put(entry.getKey(), entry.getValue().asText()));

        Map<String, List<String>> sourceRefsBySection = new LinkedHashMap<>();
        node.get("sourceRefsBySection").properties().forEach(entry -> {
            List<String> refs = new ArrayList<>();
            entry.getValue().forEach(ref -> refs.add(ref.asText()));
            sourceRefsBySection.put(entry.getKey(), refs);
        });

        return new SpecDraft(sections, toTextList(node.get("unresolvedItems")), sourceRefsBySection);
    }

    private List<String> toTextList(JsonNode array) {
        List<String> result = new ArrayList<>();
        array.forEach(element -> result.add(element.asText()));
        return result;
    }
}
