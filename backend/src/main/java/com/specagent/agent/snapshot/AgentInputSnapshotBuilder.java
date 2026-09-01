package com.specagent.agent.snapshot;

import com.specagent.agent.contract.AgentInputSnapshot;
import com.specagent.agent.contract.AgentEvent;
import com.specagent.agent.contract.AgentProtocol;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AnswerView;
import com.specagent.agent.contract.AutonomyInputs;
import com.specagent.agent.contract.CapabilityDescriptor;
import com.specagent.agent.contract.CapabilityResultView;
import com.specagent.agent.contract.ClaimView;
import com.specagent.agent.contract.DecisionBudget;
import com.specagent.agent.contract.LineageEntry;
import com.specagent.agent.contract.NodeBodyView;
import com.specagent.agent.contract.NodeView;
import com.specagent.agent.contract.OptionView;
import com.specagent.agent.contract.RelatedNodeRef;
import com.specagent.agent.contract.RelationView;
import com.specagent.agent.contract.PatchView;
import com.specagent.agent.contract.RouteContextView;
import com.specagent.agent.contract.SnapshotMetadata;
import com.specagent.context.ContextRelation;
import com.specagent.answer.Answer;
import com.specagent.answer.AnswerRepository;
import com.specagent.capability.CapabilityInvocationRecord;
import com.specagent.capability.CapabilityInvocationRepository;
import com.specagent.capability.CapabilityRegistry;
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
import java.util.Set;
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

    /** Bounded observation count: prompts never receive an unbounded catalog. */
    private static final int RECENT_CAPABILITY_RESULTS_LIMIT = 5;

    private final NodeRepository nodeRepository;
    private final AnswerRepository answerRepository;
    private final AnswerPatchRepository answerPatchRepository;
    private final RouteRepository routeRepository;
    private final RequirementStateBuilder requirementStateBuilder;
    private final CapabilityRegistry capabilityRegistry;
    private final CapabilityInvocationRepository capabilityInvocationRepository;
    private final Json json;

    public AgentInputSnapshotBuilder(NodeRepository nodeRepository,
                                     AnswerRepository answerRepository,
                                     AnswerPatchRepository answerPatchRepository,
                                     RouteRepository routeRepository,
                                     RequirementStateBuilder requirementStateBuilder,
                                     CapabilityRegistry capabilityRegistry,
                                     CapabilityInvocationRepository capabilityInvocationRepository,
                                     Json json) {
        this.nodeRepository = nodeRepository;
        this.answerRepository = answerRepository;
        this.answerPatchRepository = answerPatchRepository;
        this.routeRepository = routeRepository;
        this.requirementStateBuilder = requirementStateBuilder;
        this.capabilityRegistry = capabilityRegistry;
        this.capabilityInvocationRepository = capabilityInvocationRepository;
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
        List<Node> lineageNodes = snapshot.includedNodeIds().stream()
                .map(nodeRepository::findById)
                .map(optional -> optional.orElseThrow(() -> new IllegalStateException(
                        "Context snapshot included missing node")))
                .toList();
        List<Node> relatedNodes = loadRelatedNodes(snapshot, lineageNodes);
        List<RelatedNodeRef> relatedRefs = relatedNodeRefs(snapshot, relatedNodes);
        return new AgentInputSnapshot(
                snapshot.id().toString(),
                snapshot.contextHash(),
                snapshot.projectId(),
                snapshot.routeId(),
                snapshot.tipNodeId(),
                routeContext(snapshot),
                lineage(snapshot, lineageNodes),
                effectiveClaims(snapshot),
                metadata(snapshot),
                allowedSourceRefs(snapshot, relatedRefs),
                visibleCapabilityDescriptors(lineageNodes, relatedNodes),
                capabilityResults(snapshot),
                relations(snapshot),
                relatedRefs,
                new AutonomyInputs("ADVISOR"));
    }

    /**
     * Bounded 1-hop semantic context projected onto the wire, direction
     * preserved exactly as stored in the durable snapshot.
     */
    private List<RelationView> relations(ContextSnapshot snapshot) {
        return snapshot.relations().stream()
                .map(r -> new RelationView(r.sourceNodeId(), r.targetNodeId(), r.relationType()))
                .toList();
    }

    /**
     * The live {@link Node} domain objects at the other end of the 1-hop
     * semantic context. Every {@code snapshot.relatedNodeId()} is loaded and
     * verified: the node must still exist, belong to the snapshot's project,
     * and not be retracted. Verification failures fail the projection loudly
     * (a frozen snapshot listed a node that is no longer part of its project's
     * live graph), never silently dropping context. A related node that is
     * already part of the lineage is skipped — it is fully present in the
     * lineage already, so duplicating it as a "related" node would add no
     * context. Related nodes never enter the lineage and never pollute it.
     */
    private List<Node> loadRelatedNodes(ContextSnapshot snapshot, List<Node> lineageNodes) {
        Set<UUID> lineageIds = lineageNodes.stream().map(Node::id)
                .collect(java.util.stream.Collectors.toSet());
        List<Node> related = new ArrayList<>();
        for (UUID relatedId : snapshot.relatedNodeIds()) {
            if (lineageIds.contains(relatedId)) {
                continue;
            }
            Node node = nodeRepository.findById(relatedId)
                    .orElseThrow(() -> new IllegalStateException(
                            "Context snapshot listed missing related node: " + relatedId));
            verifyRelatedNode(snapshot, node);
            related.add(node);
        }
        return List.copyOf(related);
    }

    private void verifyRelatedNode(ContextSnapshot snapshot, Node node) {
        if (!node.projectId().equals(snapshot.projectId())) {
            throw new IllegalStateException(
                    "Context snapshot listed related node from another project: " + node.id());
        }
        if (node.isRetracted()) {
            throw new IllegalStateException(
                    "Context snapshot listed retracted related node: " + node.id());
        }
    }

    /**
     * The related canonical nodes of the 1-hop semantic context, each with
     * explicit provenance (relation type + direction relative to the anchor)
     * plus the projected NodeView/body of the related node itself — the model
     * reads real body content, never only opaque ids.
     */
    private List<RelatedNodeRef> relatedNodeRefs(ContextSnapshot snapshot, List<Node> relatedNodes) {
        UUID anchor = snapshot.tipNodeId();
        List<RelatedNodeRef> refs = new ArrayList<>();
        for (Node related : relatedNodes) {
            // Provenance is taken from the stored relation that touches the
            // related node on one end; the snapshot guarantees at least one
            // such relation exists for every listed related id.
            ContextRelation relation = snapshot.relations().stream()
                    .filter(r -> r.sourceNodeId().equals(related.id())
                            || r.targetNodeId().equals(related.id()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "Related node has no relation in snapshot context: " + related.id()));
            boolean outgoing = anchor != null && anchor.equals(relation.sourceNodeId());
            String direction = outgoing ? "OUTGOING" : "INCOMING";
            refs.add(new RelatedNodeRef(related.id(), relation.relationType(), direction,
                    nodeView(related)));
        }
        return List.copyOf(refs);
    }

    /**
     * Permission- and relevance-filtered capability descriptors. Relevance is
     * driven by each descriptor's {@code supports} declarations ("KIND" or
     * "KIND:SUBTYPE") against the context node kinds — the lineage nodes plus
     * the bounded 1-hop related nodes — a generic rule, so new capabilities
     * become visible by declaring supports, without edits to the builder or
     * planner. A directly-related RESOURCE node therefore exposes an allowed
     * capability without any workspace-wide scan. Context-free capabilities
     * (empty supports) stay visible everywhere.
     */
    private List<CapabilityDescriptor> visibleCapabilityDescriptors(List<Node> lineageNodes,
                                                                    List<Node> relatedNodes) {
        List<Node> contextNodes = new ArrayList<>(lineageNodes);
        contextNodes.addAll(relatedNodes);
        return capabilityRegistry.descriptorsFor(java.util.Set.of()).stream()
                .filter(descriptor -> supportsAnyLineageNode(descriptor, contextNodes))
                .map(descriptor -> new CapabilityDescriptor(
                        descriptor.capabilityId(),
                        descriptor.version(),
                        descriptor.description(),
                        descriptor.readOnly(),
                        descriptor.sideEffectClass().code()))
                .toList();
    }

    private boolean supportsAnyLineageNode(com.specagent.capability.CapabilityDescriptor descriptor,
                                           List<Node> lineageNodes) {
        if (descriptor.supports().isEmpty()) {
            return true;
        }
        return descriptor.supports().stream().anyMatch(support -> lineageNodes.stream()
                .anyMatch(node -> supportMatches(support, node)));
    }

    private boolean supportMatches(String support, Node node) {
        int separator = support.indexOf(':');
        if (separator < 0) {
            return support.equalsIgnoreCase(node.kind().code());
        }
        String kind = support.substring(0, separator);
        String subtype = support.substring(separator + 1);
        return kind.equalsIgnoreCase(node.kind().code())
                && subtype.equalsIgnoreCase(node.subtype());
    }

    /**
     * Recent completed capability invocations as bounded observations. They
     * are evidence for later cycles, never auto-confirmed truth; the count
     * stays small so prompts never receive an unbounded catalog.
     */
    private List<CapabilityResultView> capabilityResults(ContextSnapshot snapshot) {
        return capabilityInvocationRepository
                .findRecentCompleted(snapshot.projectId(), RECENT_CAPABILITY_RESULTS_LIMIT)
                .stream()
                .map(this::capabilityResultView)
                .toList();
    }

    @SuppressWarnings("unchecked")
    private CapabilityResultView capabilityResultView(CapabilityInvocationRecord record) {
        Map<String, Object> stored = record.result() == null ? Map.of() : record.result();
        Object content = stored.get("content");
        Object provenance = stored.get("provenance");
        Object refs = stored.get("sourceRefs");
        return new CapabilityResultView(
                record.id().toString(),
                record.capabilityId(),
                record.status().name(),
                content instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of(),
                refs instanceof List<?> list
                        ? list.stream().map(String::valueOf).toList() : List.of(),
                provenance instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of());
    }

    private RouteContextView routeContext(ContextSnapshot snapshot) {
        if (snapshot.routeId() == null) {
            // Routeless NODE_QUERY context (floating node): the read context
            // is the anchor node itself. Build the explicit route-less view
            // without any repository lookup against a null route id.
            return new RouteContextView(null, snapshot.tipNodeId(), null);
        }
        String label = routeRepository.findById(snapshot.routeId())
                .map(Route::label)
                .orElse(null);
        return new RouteContextView(snapshot.routeId(), snapshot.tipNodeId(), label);
    }

    private List<LineageEntry> lineage(ContextSnapshot snapshot, List<Node> lineageNodes) {
        Map<UUID, Answer> answersByNodeId = new HashMap<>();
        for (Answer answer : loadAnswers(snapshot)) {
            answersByNodeId.put(answer.nodeId(), answer);
        }
        Map<UUID, List<AnswerPatch>> patchesByAnswerId = groupPatchesByAnswer(snapshot);

        List<LineageEntry> lineage = new ArrayList<>();
        for (Node node : lineageNodes) {
            Answer answer = answersByNodeId.get(node.id());
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

    private List<String> allowedSourceRefs(ContextSnapshot snapshot, List<RelatedNodeRef> relatedRefs) {
        List<String> refs = new ArrayList<>();
        snapshot.includedNodeIds().forEach(id -> refs.add("node:" + id));
        snapshot.includedAnswerIds().forEach(id -> refs.add("answer:" + id));
        snapshot.includedPatchIds().forEach(id -> refs.add("patch:" + id));
        // Related nodes are first-class source refs too: a model may ground on
        // their body content or reference them in a CONNECT_NODE proposal
        // (e.g. relating the anchor to a directly-visible related node).
        relatedRefs.stream().map(RelatedNodeRef::nodeId).distinct()
                .forEach(id -> refs.add("node:" + id));
        refs.add("context:" + snapshot.id());
        if (snapshot.routeId() != null) {
            refs.add("route:" + snapshot.routeId());
        }
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
        // Interaction nodes keep their question text; other kinds expose the
        // primary content payload as text so the body stays one shape.
        String text = node.question() != null ? node.question() : node.contentText();
        return new NodeView(node.id(),
                new NodeBodyView(text, options, node.allowFreeAnswer()),
                node.kind().code());
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
