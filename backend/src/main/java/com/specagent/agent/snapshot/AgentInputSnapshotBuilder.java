package com.specagent.agent.snapshot;

import com.specagent.agent.contract.AgentInputSnapshot;
import com.specagent.agent.contract.AgentEvent;
import com.specagent.agent.contract.AgentProtocol;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AnswerView;
import com.specagent.agent.contract.AutonomyInputs;
import com.specagent.agent.contract.ClaimView;
import com.specagent.agent.contract.DecisionBudget;
import com.specagent.agent.contract.LineageEntry;
import com.specagent.agent.contract.NodeBodyView;
import com.specagent.agent.contract.NodeView;
import com.specagent.agent.contract.OptionView;
import com.specagent.agent.contract.PatchView;
import com.specagent.agent.contract.RouteContextView;
import com.specagent.agent.contract.SnapshotMetadata;
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
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Deterministic Java-side projection of a frozen {@link ContextSnapshot} into
 * the versioned {@code AgentInputSnapshot} wire contract.
 *
 * <p>The durable {@code ContextSnapshot} manifest stays untouched and remains
 * the lineage authority; this builder only projects exactly the records the
 * manifest lists, in the manifest's own order, into generic Graph language.
 * Python never reconstructs this state from database access.
 *
 * <p>{@code projectTitle} is carried only as low-authority display metadata
 * and must never be promoted to an objective by any consumer of the snapshot.
 */
@Service
public class AgentInputSnapshotBuilder {

    private final NodeRepository nodeRepository;
    private final AnswerRepository answerRepository;
    private final AnswerPatchRepository answerPatchRepository;
    private final RouteRepository routeRepository;
    private final RequirementStateBuilder requirementStateBuilder;
    private final Json json;

    public AgentInputSnapshotBuilder(NodeRepository nodeRepository,
                                     AnswerRepository answerRepository,
                                     AnswerPatchRepository answerPatchRepository,
                                     RouteRepository routeRepository,
                                     RequirementStateBuilder requirementStateBuilder,
                                     Json json) {
        this.nodeRepository = nodeRepository;
        this.answerRepository = answerRepository;
        this.answerPatchRepository = answerPatchRepository;
        this.routeRepository = routeRepository;
        this.requirementStateBuilder = requirementStateBuilder;
        this.json = json;
    }

    /**
     * Builds the complete request envelope for one frozen snapshot and run.
     */
    public AgentRequestEnvelope buildEnvelope(UUID runId,
                                                ContextSnapshot snapshot,
                                                AgentEvent event,
                                                DecisionBudget budget) {
        return new AgentRequestEnvelope(
                AgentProtocol.INPUT_PROTOCOL_VERSION,
                runId,
                event,
                build(snapshot),
                List.of(),
                budget);
    }

    /**
     * Projects one frozen snapshot into the model-facing input snapshot.
     */
    public AgentInputSnapshot build(ContextSnapshot snapshot) {
        return new AgentInputSnapshot(
                snapshot.id().toString(),
                snapshot.contextHash(),
                snapshot.projectId(),
                snapshot.routeId(),
                snapshot.tipNodeId(),
                routeContext(snapshot),
                lineage(snapshot),
                effectiveClaims(snapshot),
                metadata(snapshot),
                allowedSourceRefs(snapshot),
                List.of(),
                new AutonomyInputs("ADVISOR"));
    }

    private RouteContextView routeContext(ContextSnapshot snapshot) {
        String label = routeRepository.findById(snapshot.routeId())
                .map(Route::label)
                .orElse(null);
        return new RouteContextView(snapshot.routeId(), snapshot.tipNodeId(), label);
    }

    private List<LineageEntry> lineage(ContextSnapshot snapshot) {
        Map<UUID, Answer> answersByNodeId = new HashMap<>();
        for (Answer answer : loadAnswers(snapshot)) {
            answersByNodeId.put(answer.nodeId(), answer);
        }
        Map<UUID, List<AnswerPatch>> patchesByAnswerId = groupPatchesByAnswer(snapshot);

        List<LineageEntry> lineage = new ArrayList<>();
        for (UUID nodeId : snapshot.includedNodeIds()) {
            Node node = nodeRepository.findById(nodeId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Context snapshot included missing node: " + nodeId));
            Answer answer = answersByNodeId.get(nodeId);
            List<PatchView> patches = answer == null
                    ? List.of()
                    : patchesByAnswerId.getOrDefault(answer.id(), List.of()).stream()
                            .map(this::patchView)
                            .toList();
            lineage.add(new LineageEntry(nodeView(node),
                    answer == null ? null : answerView(answer), patches));
        }
        return lineage;
    }

    private List<ClaimView> effectiveClaims(ContextSnapshot snapshot) {
        RequirementState state = requirementStateBuilder.buildForContext(snapshot);
        return state.claims().stream().map(this::claimView).toList();
    }

    @SuppressWarnings("unchecked")
    private SnapshotMetadata metadata(ContextSnapshot snapshot) {
        String raw = snapshot.specialInputs();
        if (raw == null || raw.isBlank() || "null".equals(raw)) {
            return new SnapshotMetadata(null);
        }
        Map<String, Object> specialInputs = json.read(raw, Map.class);
        Object title = specialInputs == null ? null : specialInputs.get("projectTitle");
        return new SnapshotMetadata(title instanceof String text && !text.isBlank() ? text : null);
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

    private List<Answer> loadAnswers(ContextSnapshot snapshot) {
        List<Answer> answers = new ArrayList<>();
        for (UUID id : snapshot.includedAnswerIds()) {
            answers.add(answerRepository.findById(id)
                    .orElseThrow(() -> new IllegalStateException(
                            "Context snapshot included missing answer: " + id)));
        }
        return answers;
    }

    private Map<UUID, List<AnswerPatch>> groupPatchesByAnswer(ContextSnapshot snapshot) {
        Map<UUID, List<AnswerPatch>> byAnswer = new HashMap<>();
        for (UUID id : snapshot.includedPatchIds()) {
            AnswerPatch patch = answerPatchRepository.findById(id)
                    .orElseThrow(() -> new IllegalStateException(
                            "Context snapshot included missing patch: " + id));
            byAnswer.computeIfAbsent(patch.sourceAnswerId(), key -> new ArrayList<>()).add(patch);
        }
        return byAnswer;
    }

    /** Generic Graph-language node projection; workflow names never appear. */
    private NodeView nodeView(Node node) {
        List<OptionView> options = node.options().stream()
                .map(option -> new OptionView(option.id(), option.label()))
                .toList();
        return new NodeView(node.id(),
                new NodeBodyView(node.question(), options, node.allowFreeAnswer()));
    }

    private AnswerView answerView(Answer answer) {
        return new AnswerView(
                answer.id(),
                answer.nodeId(),
                parseOptionId(answer.selectedOptionId()),
                answer.freeText());
    }

    private UUID parseOptionId(String selectedOptionId) {
        if (selectedOptionId == null || selectedOptionId.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(selectedOptionId);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(
                    "Persisted answer carries a malformed option id: " + selectedOptionId);
        }
    }

    private PatchView patchView(AnswerPatch patch) {
        return new PatchView(patch.id(),
                patch.claims().stream().map(this::claimView).toList());
    }

    private ClaimView claimView(Claim claim) {
        return new ClaimView(
                claim.kind().code(),
                claim.text(),
                claim.status().code(),
                claim.confidence(),
                claim.sourceNodeId(),
                claim.sourceAnswerId());
    }
}
