package com.specagent.model.contract;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.AgentTaskType;
import com.specagent.agent.ModelContractException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Strict, fail-closed parser for the task-specific structured model output.
 *
 * <p>This parser validates the {@code output} object of a model response
 * against the production contract of the requested task. It never repairs,
 * coerces, or retries model output: a missing required field, wrong type,
 * unknown enum value, blank string, out-of-range confidence, runtime-owned
 * identity field, or malformed nested structure rejects the whole output with
 * a {@link ModelContractException}. The runtime then fails the agent run and
 * persists nothing derived from the rejected output.
 *
 * <p>The parser is pure: it does not query the database, does not construct or
 * persist runtime records, and never assigns ids. Runtime identity and
 * provenance mapping happen after parsing, in the agent layer.
 */
@Component
public class StructuredModelOutputParser {

    private static final Set<String> CLAIM_KINDS = Set.of(
            "goal", "stakeholder", "scope", "constraint", "success_criterion",
            "output_expectation", "risk", "assumption", "open_question", "conflict", "other");
    private static final Set<String> CLAIM_STATUSES = Set.of(
            "confirmed", "assumed", "unresolved", "rejected");
    private static final Set<String> SOURCE_REF_KINDS = Set.of(
            "node", "answer", "patch", "context", "route");
    private static final Pattern SOURCE_REF_PATTERN = Pattern.compile(
            "^[a-z_]+:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");

    private final ObjectMapper mapper;

    public StructuredModelOutputParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Parses the task-specific output object of a model response.
     *
     * @param taskType  the requested task; the parser only supports the four
     *                  production contracts and fails closed for everything else
     * @param outputJson the raw {@code output} object JSON from the model
     * @return the validated output node
     * @throws ModelContractException on any contract violation
     */
    public JsonNode parse(AgentTaskType taskType, String outputJson) {
        JsonNode root;
        try {
            root = mapper.readTree(outputJson);
        } catch (JsonProcessingException ex) {
            throw new ModelContractException("Model output is not valid JSON: " + ex.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            throw new ModelContractException("Model output must be a JSON object");
        }
        return switch (taskType) {
            case DRAFT_NODE -> parseNodeDraft(root);
            case INTERPRET_ANSWER -> parseInterpretation(root);
            case DRAFT_ANSWER_PATCH -> parseAnswerPatchDraft(root);
            case DRAFT_SPEC -> parseSpecDraft(root);
            default -> throw new ModelContractException(
                    "No structured contract for task: " + taskType.code());
        };
    }

    private JsonNode parseNodeDraft(JsonNode root) {
        requireText(root, "question", true);
        requireOptionalText(root, "purpose");
        requireBoolean(root, "allowFreeAnswer");

        JsonNode options = requireField(root, "options");
        if (!options.isArray()) {
            throw new ModelContractException("options must be an array");
        }
        for (JsonNode option : options) {
            if (!option.isObject()) {
                throw new ModelContractException("each option must be an object");
            }
            rejectRuntimeOwnedField(option, "id", "option");
            requireText(option, "label", true);
            requireText(option, "impact", true);
        }
        return root;
    }

    private JsonNode parseInterpretation(JsonNode root) {
        for (String field : List.of("confirmedTexts", "assumedTexts", "unresolvedTexts", "conflictTexts")) {
            requireStringArray(root, field);
        }
        return root;
    }

    private JsonNode parseAnswerPatchDraft(JsonNode root) {
        JsonNode claims = requireField(root, "claims");
        if (!claims.isArray()) {
            throw new ModelContractException("claims must be an array");
        }
        for (JsonNode claim : claims) {
            if (!claim.isObject()) {
                throw new ModelContractException("each claim must be an object");
            }
            rejectRuntimeOwnedField(claim, "id", "claim");
            rejectRuntimeOwnedField(claim, "sourceNodeId", "claim");
            rejectRuntimeOwnedField(claim, "sourceAnswerId", "claim");
            requireEnum(claim, "kind", CLAIM_KINDS);
            requireText(claim, "text", true);
            requireEnum(claim, "status", CLAIM_STATUSES);
            requireConfidence(claim);
        }
        return root;
    }

    private JsonNode parseSpecDraft(JsonNode root) {
        JsonNode sections = requireField(root, "sections");
        if (!sections.isObject()) {
            throw new ModelContractException("sections must be an object");
        }
        sections.properties().forEach(entry -> {
            if (!entry.getValue().isTextual() || entry.getValue().asText().isBlank()) {
                throw new ModelContractException("spec section content is required: " + entry.getKey());
            }
        });

        JsonNode unresolvedItems = requireField(root, "unresolvedItems");
        if (!unresolvedItems.isArray()) {
            throw new ModelContractException("unresolvedItems must be an array");
        }
        for (JsonNode item : unresolvedItems) {
            if (!item.isTextual() || item.asText().isBlank()) {
                throw new ModelContractException("unresolved item must be a non-blank string");
            }
        }

        JsonNode sourceRefsBySection = requireField(root, "sourceRefsBySection");
        if (!sourceRefsBySection.isObject()) {
            throw new ModelContractException("sourceRefsBySection must be an object");
        }
        sourceRefsBySection.properties().forEach(entry -> {
            JsonNode refs = entry.getValue();
            if (!refs.isArray()) {
                throw new ModelContractException("source references must be an array: " + entry.getKey());
            }
            for (JsonNode ref : refs) {
                requireSourceRef(ref, entry.getKey());
            }
        });
        return root;
    }

    private JsonNode requireField(JsonNode node, String name) {
        JsonNode field = node.get(name);
        if (field == null) {
            throw new ModelContractException("missing required field: " + name);
        }
        return field;
    }

    private void requireText(JsonNode node, String name, boolean nonBlank) {
        JsonNode field = requireField(node, name);
        if (!field.isTextual() || (nonBlank && field.asText().isBlank())) {
            throw new ModelContractException(
                    name + " must be a " + (nonBlank ? "non-blank " : "") + "string");
        }
    }

    private void requireOptionalText(JsonNode node, String name) {
        JsonNode field = node.get(name);
        if (field == null || field.isNull()) {
            return;
        }
        if (!field.isTextual()) {
            throw new ModelContractException(name + " must be a string");
        }
    }

    private void requireBoolean(JsonNode node, String name) {
        JsonNode field = requireField(node, name);
        if (!field.isBoolean()) {
            throw new ModelContractException(name + " must be a boolean");
        }
    }

    private void requireStringArray(JsonNode node, String name) {
        JsonNode field = requireField(node, name);
        if (!field.isArray()) {
            throw new ModelContractException(name + " must be an array");
        }
        for (JsonNode element : field) {
            if (!element.isTextual() || element.asText().isBlank()) {
                throw new ModelContractException(name + " elements must be non-blank strings");
            }
        }
    }

    private void requireEnum(JsonNode node, String name, Set<String> allowed) {
        JsonNode field = requireField(node, name);
        if (!field.isTextual()) {
            throw new ModelContractException(name + " must be a string");
        }
        if (!allowed.contains(field.asText())) {
            throw new ModelContractException("unknown " + name + ": " + field.asText());
        }
    }

    private void requireConfidence(JsonNode claim) {
        JsonNode field = requireField(claim, "confidence");
        if (!field.isNumber()) {
            throw new ModelContractException("claim confidence must be a number");
        }
        double value = field.doubleValue();
        if (value < 0.0 || value > 1.0) {
            throw new ModelContractException("claim confidence must be between 0.0 and 1.0: " + value);
        }
    }

    private void rejectRuntimeOwnedField(JsonNode node, String name, String container) {
        if (node.has(name)) {
            throw new ModelContractException(
                    "model must not output runtime-owned field: " + container + "." + name);
        }
    }

    private void requireSourceRef(JsonNode ref, String sectionName) {
        if (!ref.isTextual() || ref.asText().isBlank()) {
            throw new ModelContractException("source reference must be a non-blank string: " + sectionName);
        }
        String text = ref.asText();
        if (!SOURCE_REF_PATTERN.matcher(text).matches()) {
            throw new ModelContractException("malformed source reference: " + text);
        }
        int separator = text.indexOf(':');
        String kind = text.substring(0, separator);
        if (!SOURCE_REF_KINDS.contains(kind)) {
            throw new ModelContractException("unknown source reference kind: " + text);
        }
        try {
            UUID.fromString(text.substring(separator + 1));
        } catch (IllegalArgumentException ex) {
            throw new ModelContractException("malformed source reference id: " + text);
        }
    }
}
