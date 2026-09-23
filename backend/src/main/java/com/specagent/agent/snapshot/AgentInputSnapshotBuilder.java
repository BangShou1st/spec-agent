package com.specagent.agent.snapshot;

import com.specagent.agent.protocol.AgentContractException;
import com.specagent.agent.protocol.AgentContracts;
import com.specagent.agent.protocol.AgentInputSnapshot;
import com.specagent.agent.protocol.AgentEvent;
import com.specagent.agent.protocol.AgentProtocol;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.AnswerView;
import com.specagent.agent.protocol.AutonomyInputs;
import com.specagent.agent.protocol.AgentInputSnapshot;
import com.specagent.agent.protocol.AvailableSkillView;
import com.specagent.agent.protocol.SkillCatalogView;
import com.specagent.agent.protocol.CapabilityDescriptor;
import com.specagent.agent.protocol.CapabilityResultView;
import com.specagent.agent.protocol.ClaimView;
import com.specagent.agent.protocol.DecisionBudget;
import com.specagent.agent.protocol.LineageEntry;
import com.specagent.agent.protocol.NodeBodyView;
import com.specagent.agent.protocol.NodeView;
import com.specagent.agent.protocol.OptionView;
import com.specagent.agent.protocol.RelatedNodeRef;
import com.specagent.agent.protocol.RelationView;
import com.specagent.agent.protocol.PatchView;
import com.specagent.agent.protocol.RouteContextView;
import com.specagent.agent.protocol.SnapshotMetadata;
import com.specagent.agent.protocol.UserRequiredSkillView;
import com.specagent.retrieval.RetrievedContextItem;
import com.specagent.retrieval.context.RetrievalContextService;
import com.specagent.workspace.context.ContextRelation;
import com.specagent.workspace.context.ContextOperationType;
import com.specagent.workspace.answer.Answer;
import com.specagent.workspace.answer.AnswerRepository;
import com.specagent.capability.CapabilityInvocationRecord;
import com.specagent.capability.CapabilityInvocationRepository;
import com.specagent.capability.CapabilityQueryContext;
import com.specagent.capability.CapabilityRegistry;
import com.specagent.capability.CapabilityVisibilityService;
import com.specagent.skill.discovery.SkillCatalogEntry;
import com.specagent.skill.discovery.SkillDiscoveryContext;
import com.specagent.skill.discovery.SkillDiscoveryService;
import com.specagent.skill.discovery.SkillHostToolVisibility;
import com.specagent.skill.runtime.SkillSearchHostTool;
import com.specagent.common.Hashes;
import com.specagent.common.Json;
import com.specagent.workspace.context.ContextSnapshot;
import com.specagent.workspace.context.RequirementState;
import com.specagent.workspace.context.RequirementStateBuilder;
import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeRepository;
import com.specagent.workspace.patch.AnswerPatch;
import com.specagent.workspace.patch.AnswerPatchRepository;
import com.specagent.workspace.patch.Claim;
import com.specagent.workspace.route.Route;
import com.specagent.workspace.route.RouteHistoryResolver;
import com.specagent.workspace.route.RouteRepository;
import org.springframework.stereotype.Service;


import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * <p><strong>Frozen input integrity:</strong> the first projection of a
 * ContextSnapshot is persisted once as an immutable {@link
 * com.specagent.agent.snapshot.FrozenInputProjection} row (payload hash +
 * contract version, insert-if-absent). Every later projection of the SAME
 * snapshot replays that stored payload, so retry/resume/repair can never
 * silently rebuild model input from live mutable records (editable node
 * bodies, related-node bodies, route labels, capability results). Corrupted,
 * tampered, or foreign-identity frozen rows fail closed — never a live
 * rebuild.
 *
 * <p>{@code projectTitle} is carried only as low-authority display metadata
 * and must never be promoted to an objective by any consumer of the snapshot.
 */
@Service
public class AgentInputSnapshotBuilder {

    /** Bounded observation count: prompts never receive an unbounded catalog. */
    private static final int RECENT_CAPABILITY_RESULTS_LIMIT = 5;

    /**
     * Wider fetch window for visibility filtering: sibling routes may hold the
     * newest rows, so the projection scans a wider recent window and keeps the
     * newest visible ones. The projected count stays bounded by the limit above.
     */
    private static final int VISIBILITY_FETCH_LIMIT = 20;

    /**
     * Hard upper bound for a canonical frozen payload. The projection is
     * already bounded by construction (bounded lineage, 1-hop relations,
     * bounded resource excerpts, bounded capability observations); exceeding
     * this bound is a typed fail-closed failure, never a silent truncation.
     */
    private static final int MAX_FROZEN_PAYLOAD_CHARS = 1_000_000;

    private final NodeRepository nodeRepository;
    private final AnswerRepository answerRepository;
    private final AnswerPatchRepository answerPatchRepository;
    private final RouteRepository routeRepository;
    private final RouteHistoryResolver routeHistoryResolver;
    private final RequirementStateBuilder requirementStateBuilder;
    private final CapabilityRegistry capabilityRegistry;
    private final CapabilityVisibilityService capabilityVisibilityService;
    private final SkillDiscoveryService skillDiscoveryService;
    private final SkillHostToolVisibility skillHostToolVisibility;
    private final CapabilityInvocationRepository capabilityInvocationRepository;
    private final RunAttributionLookupPort runAttributionLookup;
    private final AgentInputProjectionRepository projectionRepository;
    private final MutableSourceFingerprinter fingerprinter;
    private final Json json;
    private final RetrievalContextService retrievalContextService;
    private final WorkingContextSelector workingContextSelector;

    public AgentInputSnapshotBuilder(NodeRepository nodeRepository,
                                     AnswerRepository answerRepository,
                                     AnswerPatchRepository answerPatchRepository,
                                     RouteRepository routeRepository,
                                     RouteHistoryResolver routeHistoryResolver,
                                     RequirementStateBuilder requirementStateBuilder,
                                     CapabilityRegistry capabilityRegistry,
                                     CapabilityVisibilityService capabilityVisibilityService,
                                     SkillDiscoveryService skillDiscoveryService,
                                     SkillHostToolVisibility skillHostToolVisibility,
                                     CapabilityInvocationRepository capabilityInvocationRepository,
                                     RunAttributionLookupPort runAttributionLookup,
                                     AgentInputProjectionRepository projectionRepository,
                                     MutableSourceFingerprinter fingerprinter,
                                     Json json,
                                     RetrievalContextService retrievalContextService,
                                     WorkingContextSelector workingContextSelector) {
        this.nodeRepository = nodeRepository;
        this.answerRepository = answerRepository;
        this.answerPatchRepository = answerPatchRepository;
        this.routeRepository = routeRepository;
        this.routeHistoryResolver = routeHistoryResolver;
        this.requirementStateBuilder = requirementStateBuilder;
        this.capabilityRegistry = capabilityRegistry;
        this.capabilityVisibilityService = capabilityVisibilityService;
        this.skillDiscoveryService = skillDiscoveryService;
        this.skillHostToolVisibility = skillHostToolVisibility;
        this.capabilityInvocationRepository = capabilityInvocationRepository;
        this.runAttributionLookup = runAttributionLookup;
        this.projectionRepository = projectionRepository;
        this.fingerprinter = fingerprinter;
        this.json = json;
        this.retrievalContextService = retrievalContextService;
        this.workingContextSelector = workingContextSelector;
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
                build(snapshot, event),
                List.of(),
                budget);
    }

    /**
     * Projects one frozen snapshot into the model-facing input snapshot.
     *
     * <p>Load-or-freeze: the first projection builds from the authoritative
     * records, freezes the canonical payload durably, and returns it; every
     * later projection of the same snapshot replays the stored payload
     * verbatim. Concurrent first freezes resolve through the unique snapshot
     * index (first-writer-wins); a loser returns the winner's frozen payload.
     */
    public AgentInputSnapshot build(ContextSnapshot snapshot) {
        return build(snapshot, null);
    }

    private AgentInputSnapshot build(ContextSnapshot snapshot, AgentEvent event) {
        Set<UUID> mandatoryNodeIds = new HashSet<>();
        if (snapshot.tipNodeId() != null) {
            mandatoryNodeIds.add(snapshot.tipNodeId());
        }
        if (event != null && event.anchorNodeId() != null) {
            mandatoryNodeIds.add(event.anchorNodeId());
        }
        return projectionRepository.findBySnapshotId(snapshot.id())
                .<AgentInputSnapshot>map(frozen -> loadFrozen(snapshot, frozen))
                .orElseGet(() -> {
                    LiveProjection projection = buildFromLiveRecords(snapshot, mandatoryNodeIds);
                    return freezeOrAdopt(snapshot, projection);
                });
    }

    /**
     * Verifies and replays a durable frozen projection. Every check fails
     * closed with a typed corruption failure — never a live rebuild — because
     * the frozen payload is the audit/reproducibility evidence of what the
     * model saw.
     */
    private AgentInputSnapshot loadFrozen(ContextSnapshot snapshot,
                                          AgentInputProjectionRepository.FrozenInputProjection frozen) {
        boolean versionOk = AgentInputProjectionRepository.SUPPORTED_PROJECTION_VERSION.equals(frozen.projectionVersion())
                || AgentInputProjectionRepository.LEGACY_PROJECTION_VERSION_V1.equals(frozen.projectionVersion())
                || "agent-input.v2".equals(frozen.projectionVersion());
        if (!versionOk) {
            throw new FrozenProjectionCorruptedException(
                    "Frozen input projection carries unsupported version '"
                            + frozen.projectionVersion() + "' for snapshot " + snapshot.id());
        }
        if (!Hashes.sha256Hex(frozen.payload()).equals(frozen.payloadHash())) {
            throw new FrozenProjectionCorruptedException(
                    "Frozen input projection payload hash mismatch for snapshot "
                            + snapshot.id() + " — payload was tampered with or corrupted");
        }
        AgentInputSnapshot projection;
        try {
            projection = AgentContracts.read(frozen.payload(), AgentInputSnapshot.class);
        } catch (AgentContractException ex) {
            throw new FrozenProjectionCorruptedException(
                    "Frozen input projection payload does not parse for snapshot "
                            + snapshot.id() + ": " + ex.getMessage());
        }
        if (!snapshot.id().toString().equals(projection.snapshotId())
                || !snapshot.contextHash().equals(projection.contextHash())) {
            throw new FrozenProjectionCorruptedException(
                    "Frozen input projection identity mismatch for snapshot "
                            + snapshot.id() + " — payload belongs to another snapshot context");
        }
        return projection;
    }

    /**
     * Persists the first freeze of a snapshot. When a concurrent racer won
     * (insert-if-absent lost), this build is discarded and the winner's frozen
     * payload is loaded and returned instead — concurrent first freezes never
     * diverge and never last-writer-win.
     */
    private AgentInputSnapshot freezeOrAdopt(ContextSnapshot snapshot,
                                             LiveProjection liveProjection) {
        AgentInputSnapshot projection = liveProjection.snapshot();
        String payload = AgentContracts.write(projection);
        if (payload.length() > MAX_FROZEN_PAYLOAD_CHARS) {
            throw new FrozenProjectionSizeExceededException(
                    "Frozen input projection exceeds the "
                            + MAX_FROZEN_PAYLOAD_CHARS + " char bound for snapshot "
                            + snapshot.id() + ": " + payload.length());
        }
        List<MutableSourceFingerprint> fingerprints = fingerprinter.fingerprintsFor(
                liveProjection.workingNodes(), liveProjection.relatedNodes());
        boolean won = projectionRepository.insertIfAbsent(
                new AgentInputProjectionRepository.FrozenInputProjection(
                        UUID.randomUUID(), snapshot.id(),
                        AgentInputProjectionRepository.SUPPORTED_PROJECTION_VERSION,
                        payload, Hashes.sha256Hex(payload), fingerprints, Instant.now()));
        if (!won) {
            AgentInputProjectionRepository.FrozenInputProjection winner =
                    projectionRepository.findBySnapshotId(snapshot.id())
                            .orElseThrow(() -> new FrozenProjectionCorruptedException(
                                    "Lost the freeze race but no frozen projection row exists for snapshot "
                                            + snapshot.id()));
            return loadFrozen(snapshot, winner);
        }
        return projection;
    }

    /**
     * Projects exactly the manifest-listed records from the live
     * authoritative stores. Called at most once per snapshot identity — the
     * first freeze — and never again for that snapshot.
     */
    private LiveProjection buildFromLiveRecords(ContextSnapshot snapshot,
                                                Set<UUID> mandatoryNodeIds) {
        List<Node> manifestNodes = snapshot.includedNodeIds().stream()
                .map(nodeRepository::findById)
                .map(optional -> optional.orElseThrow(() -> new IllegalStateException(
                        "Context snapshot included missing node")))
                .toList();
        List<Answer> answers = loadAnswers(snapshot);
        Map<UUID, Answer> answersByNodeId = answers.stream()
                .collect(java.util.stream.Collectors.toMap(Answer::nodeId, answer -> answer,
                        (left, right) -> right, LinkedHashMap::new));
        Map<UUID, List<AnswerPatch>> patchesByAnswerId = groupPatchesByAnswer(snapshot);
        List<Node> workingLineageNodes = selectWorkingNodes(snapshot, manifestNodes,
                mandatoryNodeIds, answersByNodeId, patchesByAnswerId);
        List<Node> allContextNodes = new ArrayList<>(manifestNodes);
        for (Node node : workingLineageNodes) {
            if (allContextNodes.stream().noneMatch(existing -> existing.id().equals(node.id()))) {
                allContextNodes.add(node);
            }
        }
        List<Node> relatedNodes = loadRelatedNodes(snapshot, allContextNodes);
        List<RelatedNodeRef> relatedRefs = relatedNodeRefs(snapshot, relatedNodes);
        // One Skill discovery projection per first-freeze build: the same
        // catalog feeds both the wire field and the skill.search visibility
        // gate, so the two can never disagree (and a future heavier retriever
        // cannot produce two different catalogs for one frozen snapshot).
        SkillCatalogView skillCatalog =
                availableSkills(snapshot, workingLineageNodes, relatedNodes);
        List<ClaimView> effectiveClaims = effectiveClaims(snapshot);
        String retrievalQueryText = retrievalQueryText(snapshot, allContextNodes, effectiveClaims);
        Set<String> mandatoryRetrievalRefs = new HashSet<>();
        effectiveClaims.forEach(claim -> {
            if (claim.sourceAnswerId() != null) {
                mandatoryRetrievalRefs.add("answer:" + claim.sourceAnswerId());
            }
            if (claim.sourceNodeId() != null) {
                mandatoryRetrievalRefs.add("node:" + claim.sourceNodeId());
            }
        });
        List<RetrievedContextItem> retrievedContext = retrievalContextService.retrieve(
                snapshot, mandatoryRetrievalRefs, retrievalQueryText);
        AgentInputSnapshot projection = new AgentInputSnapshot(
                snapshot.id().toString(),
                snapshot.contextHash(),
                snapshot.projectId(),
                snapshot.routeId(),
                snapshot.tipNodeId(),
                routeContext(snapshot),
                lineage(workingLineageNodes, answersByNodeId, patchesByAnswerId),
                effectiveClaims,
                metadata(snapshot),
                allowedSourceRefs(snapshot, workingLineageNodes, effectiveClaims,
                        relatedRefs, retrievedContext),
                visibleCapabilityDescriptors(snapshot, workingLineageNodes, relatedNodes, skillCatalog),
                skillCatalog,
                capabilityResults(snapshot),
                relations(snapshot),
                relatedRefs,
                retrievedContext,
                new AutonomyInputs("ADVISOR"));
        return new LiveProjection(projection, workingLineageNodes, relatedNodes);
    }

    /**
     * Separates the route's authoritative parent lineage from the manifest's
     * derived material before applying the working-memory bounds. Route
     * membership comes from RouteHistoryResolver, never from a parent-chain
     * guess made by the retrieval projection.
     */
    private List<Node> selectWorkingNodes(ContextSnapshot snapshot,
                                          List<Node> manifestNodes,
                                          Set<UUID> mandatoryNodeIds,
                                          Map<UUID, Answer> answersByNodeId,
                                          Map<UUID, List<AnswerPatch>> patchesByAnswerId) {
        Map<UUID, Node> allById = new LinkedHashMap<>();
        manifestNodes.forEach(node -> allById.put(node.id(), node));
        if (snapshot.tipNodeId() != null) {
            nodeRepository.findById(snapshot.tipNodeId()).ifPresent(node -> allById.put(node.id(), node));
        }
        if (mandatoryNodeIds != null) {
            for (UUID mandatoryId : mandatoryNodeIds) {
                if (mandatoryId == null) {
                    continue;
                }
                Node node = nodeRepository.findById(mandatoryId)
                        .orElseThrow(() -> new IllegalStateException(
                                "Working context mandatory node is missing: " + mandatoryId));
                if (!snapshot.projectId().equals(node.projectId())) {
                    throw new IllegalStateException(
                            "Working context mandatory node belongs to another project: " + mandatoryId);
                }
                allById.put(node.id(), node);
            }
        }

        List<Node> canonicalRouteLineage;
        if (snapshot.tipNodeId() == null) {
            canonicalRouteLineage = manifestNodes;
        } else {
            canonicalRouteLineage = routeHistoryResolver.resolveLineage(snapshot.tipNodeId()).stream()
                    .map(id -> allById.computeIfAbsent(id, key -> nodeRepository.findById(key)
                            .orElseThrow(() -> new IllegalStateException(
                                    "Working context lineage node is missing: " + key))))
                    .toList();
        }
        Set<UUID> canonicalIds = canonicalRouteLineage.stream()
                .map(Node::id).collect(java.util.stream.Collectors.toSet());
        List<Node> derivedMaterial = allById.values().stream()
                .filter(node -> !canonicalIds.contains(node.id()))
                .toList();
        return workingContextSelector.select(
                canonicalRouteLineage,
                derivedMaterial,
                mandatoryNodeIds,
                nodes -> modelFacingLineageChars(nodes, answersByNodeId, patchesByAnswerId));
    }

    private int modelFacingLineageChars(List<Node> nodes,
                                        Map<UUID, Answer> answersByNodeId,
                                        Map<UUID, List<AnswerPatch>> patchesByAnswerId) {
        return AgentContracts.write(lineage(nodes, answersByNodeId, patchesByAnswerId)).length();
    }

    @SuppressWarnings("unchecked")
    private String retrievalQueryText(ContextSnapshot snapshot,
                                      List<Node> lineageNodes,
                                      List<ClaimView> claims) {
        List<String> parts = new ArrayList<>();
        List<String> explicitUserQueries = new ArrayList<>();
        if (snapshot.specialInputs() != null && !snapshot.specialInputs().isBlank()) {
            Map<String, Object> inputs = json.read(snapshot.specialInputs(), Map.class);
            if (inputs != null) {
                for (String key : List.of("userQuestion", "userInstruction", "oldQuestion", "oldPurpose")) {
                    Object value = inputs.get(key);
                    if (value instanceof String text && !text.isBlank()) {
                        parts.add(text);
                        if (snapshot.operationType() == ContextOperationType.NODE_QUERY) {
                            explicitUserQueries.add(text);
                        }
                    }
                }
            }
        }
        // A NODE_QUERY's explicit question is the retrieval intent. Appending
        // the current tip body to that text can dilute pg_trgm word similarity
        // enough to hide an older matching fact, while the tip remains present
        // in mandatory working context already.
        if (snapshot.operationType() == ContextOperationType.NODE_QUERY
                && !explicitUserQueries.isEmpty()) {
            return String.join("\n", explicitUserQueries);
        }
        if (snapshot.tipNodeId() != null) {
            lineageNodes.stream()
                    .filter(node -> snapshot.tipNodeId().equals(node.id()))
                    .findFirst()
                    .ifPresent(node -> {
                        if (node.question() != null && !node.question().isBlank()) {
                            parts.add(node.question());
                        } else if (node.contentText() != null && !node.contentText().isBlank()) {
                            parts.add(node.contentText());
                        }
                    });
        }
        claims.stream().limit(24).map(ClaimView::text)
                .filter(text -> text != null && !text.isBlank()).forEach(parts::add);
        return String.join("\n", parts);
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
     * Permission-, availability- and relevance-filtered capability descriptors
     * projected onto the wire contract. Filtering (permissions, provider
     * availability, {@code supports} compatibility against the context node
     * kinds, catalog bounds) is owned by
     * {@link CapabilityVisibilityService}; this builder only maps the bounded
     * runtime descriptor onto the versioned wire shape — including the bounded
     * input schema and the {@code supports} facts that drove visibility — so
     * the model can construct valid calls for dynamic providers without ever
     * seeing implementation classes, connections, endpoints or credentials.
     */
    private List<CapabilityDescriptor> visibleCapabilityDescriptors(ContextSnapshot snapshot,
                                                                    List<Node> lineageNodes,
                                                                    List<Node> relatedNodes,
                                                                    SkillCatalogView skillCatalog) {
        List<String> contextKinds = new ArrayList<>();
        for (Node node : lineageNodes) {
            contextKinds.add(node.kind().code());
            if (node.subtype() != null && !node.subtype().isBlank()) {
                contextKinds.add(node.kind().code() + ":" + node.subtype());
            }
        }
        for (Node node : relatedNodes) {
            contextKinds.add(node.kind().code());
            if (node.subtype() != null && !node.subtype().isBlank()) {
                contextKinds.add(node.kind().code() + ":" + node.subtype());
            }
        }
        CapabilityQueryContext context = new CapabilityQueryContext(
                Set.of(), List.copyOf(contextKinds), Map.of());
        boolean skillsPresent = skillHostToolVisibility.anyEnabledSkill(snapshot.projectId());
        // skill.search is a truncation fallback only: it stays hidden unless
        // the single Skill catalog projection for this build was truncated.
        // The precomputed catalog is passed in — no second discovery call.
        boolean catalogTruncated = skillCatalog.truncated();
        return capabilityVisibilityService.visibleCapabilities(context).stream()
                .filter(descriptor -> skillsPresent
                        || !isSkillHostTool(descriptor.capabilityId()))
                .filter(descriptor -> catalogTruncated
                        || !SkillSearchHostTool.CAPABILITY_ID.equals(descriptor.capabilityId()))
                .map(descriptor -> new CapabilityDescriptor(
                        descriptor.capabilityId(),
                        descriptor.version(),
                        descriptor.description(),
                        descriptor.inputSchema(),
                        descriptor.readOnly(),
                        descriptor.sideEffectClass().code(),
                        descriptor.supports()))
                .toList();
    }

    /**
     * Skill Host Function Tools are exposed only when the project actually has
     * an enabled Skill to activate/read — "installed != loaded" applies to the
     * procedural-knowledge tools just as it does to Skill bodies. The check is
     * a deterministic structured fact (a registered capability id prefix),
     * never user wording.
     */
    private boolean isSkillHostTool(String capabilityId) {
        return capabilityId.startsWith("skill.");
    }

    /**
     * Bounded Skill catalog for one fresh Decision context. Discovery runs
     * here — at first-freeze time — so every fresh continuation snapshot gets
     * a freshly discovered catalog while the same frozen snapshot always
     * replays the identical frozen projection. Only identity + bounded
     * metadata cross the boundary; full SKILL.md stays behind activation.
     */
    private SkillCatalogView availableSkills(ContextSnapshot snapshot,
                                            List<Node> lineageNodes,
                                            List<Node> relatedNodes) {
        List<String> resourceKinds = new ArrayList<>();
        for (Node node : lineageNodes) {
            resourceKinds.add(node.kind().code());
        }
        for (Node node : relatedNodes) {
            resourceKinds.add(node.kind().code());
        }
        List<String> recentCapabilityIds = capabilityInvocationRepository
                .findRecentCompleted(snapshot.projectId(), RECENT_CAPABILITY_RESULTS_LIMIT)
                .stream().map(CapabilityInvocationRecord::capabilityId).toList();
        SkillDiscoveryContext context = new SkillDiscoveryContext(
                snapshot.operationType() == null ? null : snapshot.operationType().name(),
                List.copyOf(resourceKinds), recentCapabilityIds, Map.of());
        var projection = skillDiscoveryService.discover(context);
        List<AvailableSkillView> skills = projection.entries().stream()
                .map(this::toSkillView)
                .toList();
        return new SkillCatalogView(skills, projection.truncated(),
                projection.fingerprint(), userRequiredSkill(lineageNodes, relatedNodes, skills));
    }

    /**
     * The user's explicit skill directive for this context, or null. Nodes
     * edited through the "/" skill picker persist the picked skill under the
     * {@code skillId} content key; the directive only survives into the wire
     * snapshot when that id is present in the discovered (enabled) catalog,
     * so a disabled or removed skill is silently downgraded to absent instead
     * of reaching the model as an unenforceable instruction. Lineage nodes
     * win over related nodes; among several bound nodes the first in
     * deterministic lineage order wins.
     */
    private UserRequiredSkillView userRequiredSkill(List<Node> lineageNodes,
                                                    List<Node> relatedNodes,
                                                    List<AvailableSkillView> skills) {
        for (Node node : lineageNodes) {
            UserRequiredSkillView directive = boundDirective(node, skills);
            if (directive != null) {
                return directive;
            }
        }
        for (Node node : relatedNodes) {
            UserRequiredSkillView directive = boundDirective(node, skills);
            if (directive != null) {
                return directive;
            }
        }
        return null;
    }

    private UserRequiredSkillView boundDirective(Node node, List<AvailableSkillView> skills) {
        Object raw = node.content().get("skillId");
        if (!(raw instanceof String skillId) || skillId.isBlank()) {
            return null;
        }
        return skills.stream()
                .filter(skill -> skill.skillId().equalsIgnoreCase(skillId.strip()))
                .findFirst()
                .map(skill -> new UserRequiredSkillView(skill.skillId(), skill.name()))
                .orElse(null);
    }

    private AvailableSkillView toSkillView(SkillCatalogEntry entry) {
        return new AvailableSkillView(entry.skillId(), entry.name(),
                entry.description(), entry.compatibilityHint());
    }

    /**
     * Recent completed capability invocations as bounded observations. They
     * are evidence for later cycles, never auto-confirmed truth; the count
     * stays small so prompts never receive an unbounded catalog.
     */
    private List<CapabilityResultView> capabilityResults(ContextSnapshot snapshot) {
        List<CapabilityInvocationRecord> recent = capabilityInvocationRepository
                .findRecentCompleted(snapshot.projectId(), VISIBILITY_FETCH_LIMIT);
        Set<UUID> lineage = new java.util.HashSet<>(snapshot.includedNodeIds());
        Map<UUID, CapabilityObservationVisibility.RunAttribution> runsById = new HashMap<>();
        for (CapabilityInvocationRecord record : recent) {
            if (record.runId() != null && !runsById.containsKey(record.runId())) {
                runsById.put(record.runId(), loadRunAttribution(record.runId()));
            }
        }
        return recent.stream()
                .filter(record -> CapabilityObservationVisibility.isVisible(
                        snapshot.routeId(), lineage,
                        runsById.get(record.runId()),
                        CapabilityObservationVisibility.referencedNodeIds(record)))
                .limit(RECENT_CAPABILITY_RESULTS_LIMIT)
                .map(this::capabilityResultView)
                .toList();
    }

    private CapabilityObservationVisibility.RunAttribution loadRunAttribution(UUID runId) {
        return runAttributionLookup.attributionOf(runId).orElse(null);
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

    private List<LineageEntry> lineage(List<Node> lineageNodes,
                                       Map<UUID, Answer> answersByNodeId,
                                       Map<UUID, List<AnswerPatch>> patchesByAnswerId) {
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

    private List<String> allowedSourceRefs(ContextSnapshot snapshot,
                                           List<Node> workingLineageNodes,
                                           List<ClaimView> effectiveClaims,
                                           List<RelatedNodeRef> relatedRefs,
                                           List<RetrievedContextItem> retrievedContext) {
        List<String> refs = new ArrayList<>();
        Set<UUID> workingNodeIds = workingLineageNodes.stream()
                .map(Node::id).collect(java.util.stream.Collectors.toSet());
        Map<UUID, Answer> answersByNodeId = new HashMap<>();
        for (Answer answer : loadAnswers(snapshot)) {
            if (workingNodeIds.contains(answer.nodeId())) {
                refs.add("answer:" + answer.id());
                answersByNodeId.put(answer.nodeId(), answer);
            }
        }
        Map<UUID, List<AnswerPatch>> patchesByAnswerId = groupPatchesByAnswer(snapshot);
        workingLineageNodes.forEach(node -> refs.add("node:" + node.id()));
        answersByNodeId.values().stream()
                .flatMap(answer -> patchesByAnswerId.getOrDefault(answer.id(), List.of()).stream())
                .map(patch -> "patch:" + patch.id())
                .forEach(refs::add);
        effectiveClaims.stream().flatMap(claim -> java.util.stream.Stream.of(
                        claim.sourceNodeId() == null ? null : "node:" + claim.sourceNodeId(),
                        claim.sourceAnswerId() == null ? null : "answer:" + claim.sourceAnswerId()))
                .filter(java.util.Objects::nonNull)
                .forEach(refs::add);
        // Related nodes are first-class source refs too: a model may ground on
        // their body content or reference them in a CONNECT_NODE proposal
        // (e.g. relating the anchor to a directly-visible related node).
        relatedRefs.stream().map(RelatedNodeRef::nodeId).distinct()
                .forEach(id -> refs.add("node:" + id));
        if (retrievedContext != null) {
            retrievedContext.stream().map(RetrievedContextItem::sourceRef).distinct()
                    .forEach(refs::add);
        }
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

    private record LiveProjection(AgentInputSnapshot snapshot,
                                  List<Node> workingNodes,
                                  List<Node> relatedNodes) {
        private LiveProjection {
            workingNodes = workingNodes == null ? List.of() : List.copyOf(workingNodes);
            relatedNodes = relatedNodes == null ? List.of() : List.copyOf(relatedNodes);
        }
    }
}
