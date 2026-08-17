package com.specagent.agent;

import com.specagent.agent.contracts.AnswerInterpretationResult;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerRepository;
import com.specagent.common.Json;
import com.specagent.context.ContextSnapshot;
import com.specagent.context.RequirementState;
import com.specagent.context.RequirementStateBuilder;
import com.specagent.node.Node;
import com.specagent.node.NodeRepository;
import com.specagent.patch.AnswerPatch;
import com.specagent.patch.AnswerPatchRepository;
import com.specagent.patch.Claim;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Runtime-side projection of a frozen context snapshot into the model input
 * envelope {@code {"context": {...}, "taskInput": {...}}}.
 *
 * <p>The projection is built entirely from the frozen snapshot's inclusion
 * manifest: only the snapshot's included nodes, answers, and patches enter the
 * lineage, in the snapshot's own order. Requirement state is derived with
 * {@link RequirementStateBuilder#buildForContext} only — never from live route
 * state, sibling routes, or the latest spec snapshot.
 *
 * <p>The projection is minimal: timestamps, creator identity, credentials,
 * API keys, agent run traces, excluded route content, and raw repository
 * records are never included. The model sees content and provenance only, and
 * may only reference records listed in {@code allowedSourceRefs}.
 */
@Service
public class ModelContextProjectionBuilder {

    private final NodeRepository nodeRepository;
    private final AnswerRepository answerRepository;
    private final AnswerPatchRepository answerPatchRepository;
    private final RequirementStateBuilder requirementStateBuilder;
    private final Json json;

    public ModelContextProjectionBuilder(NodeRepository nodeRepository,
                                         AnswerRepository answerRepository,
                                         AnswerPatchRepository answerPatchRepository,
                                         RequirementStateBuilder requirementStateBuilder,
                                         Json json) {
        this.nodeRepository = nodeRepository;
        this.answerRepository = answerRepository;
        this.answerPatchRepository = answerPatchRepository;
        this.requirementStateBuilder = requirementStateBuilder;
        this.json = json;
    }

    /**
     * Builds the complete model input envelope for one frozen snapshot.
     */
    public String buildInputJson(ContextSnapshot snapshot, Map<String, Object> taskInput) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("context", buildContext(snapshot));
        envelope.put("taskInput", taskInput == null ? Map.of() : taskInput);
        return json.write(envelope);
    }

    /**
     * Builds the {@code context} part of the model input envelope.
     */
    public Map<String, Object> buildContext(ContextSnapshot snapshot) {
        List<Node> nodes = loadNodes(snapshot.includedNodeIds());
        Map<UUID, Answer> answersByNodeId = new HashMap<>();
        for (Answer answer : loadAnswers(snapshot)) {
            answersByNodeId.put(answer.nodeId(), answer);
        }
        Map<UUID, List<AnswerPatch>> patchesByAnswerId = groupPatchesByAnswer(snapshot);

        List<Map<String, Object>> lineage = new ArrayList<>();
        for (Node node : nodes) {
            Answer answer = answersByNodeId.get(node.id());
            List<AnswerPatch> patches = answer == null
                    ? List.of()
                    : patchesByAnswerId.getOrDefault(answer.id(), List.of());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("node", nodeView(node));
            entry.put("answer", answer == null ? null : answerView(answer));
            entry.put("patches", patches.stream().map(this::patchView).toList());
            lineage.add(entry);
        }

        RequirementState state = requirementStateBuilder.buildForContext(snapshot);

        Map<String, Object> context = new LinkedHashMap<>();
        context.put("snapshotId", snapshot.id());
        context.put("projectId", snapshot.projectId());
        context.put("routeId", snapshot.routeId());
        context.put("tipNodeId", snapshot.tipNodeId());
        context.put("operationType", snapshot.operationType().code());
        context.put("contextHash", snapshot.contextHash());
        context.put("lineage", lineage);
        context.put("requirementState", Map.of("claims",
                state.claims().stream().map(this::claimView).toList()));
        context.put("specialInputs", specialInputsView(snapshot));
        context.put("allowedSourceRefs", allowedSourceRefs(snapshot));
        return context;
    }

    /**
     * Task input for the initial DRAFT_NODE call (no answer yet).
     */
    public Map<String, Object> initialNodeTaskInput() {
        return Map.of("mode", "initial");
    }

    /**
     * Task input for INTERPRET_ANSWER: the run-local answer being interpreted.
     */
    public Map<String, Object> answerTaskInput(Answer answer) {
        return Map.of("answer", answerView(answer));
    }

    /**
     * Task input for DRAFT_ANSWER_PATCH: the answer plus the interpretation it
     * was interpreted with.
     */
    public Map<String, Object> interpretationTaskInput(Answer answer,
                                                       AnswerInterpretationResult interpretation) {
        Map<String, Object> taskInput = new LinkedHashMap<>();
        taskInput.put("answer", answerView(answer));
        taskInput.put("interpretation", Map.of(
                "confirmedTexts", interpretation.confirmedTexts(),
                "assumedTexts", interpretation.assumedTexts(),
                "unresolvedTexts", interpretation.unresolvedTexts(),
                "conflictTexts", interpretation.conflictTexts()));
        return taskInput;
    }

    /**
     * Task input for the post-answer DRAFT_NODE call: the answer and the
     * accepted, persisted answer patch it must extend from.
     */
    public Map<String, Object> afterAnswerNodeTaskInput(Answer answer, AnswerPatch patch) {
        Map<String, Object> taskInput = new LinkedHashMap<>();
        taskInput.put("mode", "after_answer");
        taskInput.put("answer", answerView(answer));
        taskInput.put("acceptedPatch", patchView(patch));
        return taskInput;
    }

    /**
     * Minimal claim view for the model: content and provenance, never the
     * runtime-owned claim id.
     */
    public Map<String, Object> claimView(Claim claim) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("kind", claim.kind().code());
        view.put("text", claim.text());
        view.put("status", claim.status().code());
        view.put("confidence", claim.confidence());
        view.put("sourceNodeId", claim.sourceNodeId());
        view.put("sourceAnswerId", claim.sourceAnswerId());
        return view;
    }

    private List<Node> loadNodes(List<UUID> nodeIds) {
        List<Node> nodes = new ArrayList<>();
        for (UUID id : nodeIds) {
            nodeRepository.findById(id).ifPresent(nodes::add);
        }
        return nodes;
    }

    private List<Answer> loadAnswers(ContextSnapshot snapshot) {
        Map<UUID, Answer> byId = new HashMap<>();
        for (Answer answer : answerRepository.findByRouteAndNodeIds(
                snapshot.routeId(), snapshot.includedNodeIds())) {
            byId.put(answer.id(), answer);
        }
        List<Answer> ordered = new ArrayList<>();
        for (UUID id : snapshot.includedAnswerIds()) {
            Answer answer = byId.get(id);
            if (answer != null) {
                ordered.add(answer);
            }
        }
        return ordered;
    }

    private Map<UUID, List<AnswerPatch>> groupPatchesByAnswer(ContextSnapshot snapshot) {
        Map<UUID, List<AnswerPatch>> byAnswer = new HashMap<>();
        for (AnswerPatch patch : answerPatchRepository.findByIdsPreservingOrder(
                snapshot.includedPatchIds())) {
            byAnswer.computeIfAbsent(patch.sourceAnswerId(), key -> new ArrayList<>()).add(patch);
        }
        return byAnswer;
    }

    private Map<String, Object> nodeView(Node node) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", node.id());
        view.put("question", node.question());
        view.put("purpose", node.purpose());
        view.put("options", node.options().stream().map(option -> {
            Map<String, Object> optionView = new LinkedHashMap<>();
            optionView.put("id", option.id());
            optionView.put("label", option.label());
            optionView.put("impact", option.impact());
            return optionView;
        }).toList());
        view.put("allowFreeAnswer", node.allowFreeAnswer());
        return view;
    }

    private Map<String, Object> answerView(Answer answer) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", answer.id());
        view.put("nodeId", answer.nodeId());
        view.put("selectedOptionId", answer.selectedOptionId());
        view.put("freeText", answer.freeText());
        return view;
    }

    private Map<String, Object> patchView(AnswerPatch patch) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", patch.id());
        view.put("claims", patch.claims().stream().map(this::claimView).toList());
        return view;
    }

    @SuppressWarnings("unchecked")
    private Object specialInputsView(ContextSnapshot snapshot) {
        String raw = snapshot.specialInputs();
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return Map.of();
        }
        return json.read(raw, Map.class);
    }

    private List<String> allowedSourceRefs(ContextSnapshot snapshot) {
        List<String> refs = new ArrayList<>();
        snapshot.includedNodeIds().forEach(id -> refs.add("node:" + id));
        snapshot.includedAnswerIds().forEach(id -> refs.add("answer:" + id));
        snapshot.includedPatchIds().forEach(id -> refs.add("patch:" + id));
        refs.add("context:" + snapshot.id());
        refs.add("route:" + snapshot.routeId());
        return refs;
    }
}
