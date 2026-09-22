package com.specagent.retrieval.index;

import com.specagent.answer.Answer;
import com.specagent.answer.AnswerRepository;
import com.specagent.common.Hashes;
import com.specagent.common.Json;
import com.specagent.node.KnowledgeStatus;
import com.specagent.node.Node;
import com.specagent.node.NodeAuthorKind;
import com.specagent.node.NodeKind;
import com.specagent.node.NodeRepository;
import com.specagent.node.NodeIndexPort;
import com.specagent.answer.AnswerIndexPort;
import com.specagent.patch.AnswerPatch;
import com.specagent.patch.AnswerPatchIndexPort;
import com.specagent.patch.AnswerPatchRepository;
import com.specagent.patch.Claim;
import com.specagent.patch.ClaimStatus;
import com.specagent.retrieval.api.MemoryAuthority;
import com.specagent.retrieval.api.RetrievalScope;
import com.specagent.retrieval.api.RetrievalSourceKind;
import com.specagent.retrieval.persistence.RetrievalEntry;
import com.specagent.retrieval.persistence.RetrievalEntryRepository;
import com.specagent.route.Route;
import com.specagent.route.RouteHistoryResolver;
import com.specagent.route.RouteMembershipProjectionPort;
import com.specagent.route.RouteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Projects canonical Runtime records into the rebuildable retrieval index. */
@Service
public class RetrievalSourceProjector implements RouteMembershipProjectionPort {

    private final NodeRepository nodeRepository;
    private final AnswerRepository answerRepository;
    private final AnswerPatchRepository answerPatchRepository;
    private final RouteRepository routeRepository;
    private final RouteHistoryResolver routeHistoryResolver;
    private final RetrievalEntryRepository entryRepository;
    private final Json json;
    private final ResourceChunker chunker = new ResourceChunker();
    private final RetrievalSourcePolicy sourcePolicy = new RetrievalSourcePolicy();

    public RetrievalSourceProjector(NodeRepository nodeRepository,
                                    AnswerRepository answerRepository,
                                    AnswerPatchRepository answerPatchRepository,
                                    RouteRepository routeRepository,
                                    RouteHistoryResolver routeHistoryResolver,
                                    RetrievalEntryRepository entryRepository,
                                    Json json) {
        this.nodeRepository = nodeRepository;
        this.answerRepository = answerRepository;
        this.answerPatchRepository = answerPatchRepository;
        this.routeRepository = routeRepository;
        this.routeHistoryResolver = routeHistoryResolver;
        this.entryRepository = entryRepository;
        this.json = json;
    }

    /**
     * Project the current canonical workspace. Rebuild is intentionally
     * idempotent and may be used after index corruption; it never mutates
     * nodes, answers, patches, routes, or graph operations.
     */
    @Transactional
    public void rebuildProject(UUID projectId) {
        entryRepository.deleteProject(projectId);
        for (Node node : nodeRepository.findByProject(projectId)) {
            indexNode(node);
        }
        for (Answer answer : answerRepository.findByProject(projectId)) {
            indexAnswer(answer);
        }
        for (AnswerPatch patch : answerPatchRepository.findByProject(projectId)) {
            indexPatch(patch);
        }
    }

    @Transactional
    public void rebuildSource(UUID projectId, String sourceRef) {
        if (sourceRef == null || sourceRef.isBlank()) {
            throw new IllegalArgumentException("sourceRef is required");
        }
        if (sourceRef.startsWith("node:")) {
            UUID id = parseRef(sourceRef, "node:");
            nodeRepository.findById(id).filter(node -> node.projectId().equals(projectId))
                    .ifPresent(this::indexNode);
            return;
        }
        if (sourceRef.startsWith("resource-chunk:")) {
            String[] parts = sourceRef.split(":");
            if (parts.length < 2) {
                throw new IllegalArgumentException("Malformed resource sourceRef");
            }
            UUID id = UUID.fromString(parts[1]);
            nodeRepository.findById(id).filter(node -> node.projectId().equals(projectId))
                    .ifPresent(this::indexNode);
            return;
        }
        if (sourceRef.startsWith("answer:")) {
            UUID id = parseRef(sourceRef, "answer:");
            answerRepository.findById(id).filter(answer -> answer.projectId().equals(projectId))
                    .ifPresent(this::indexAnswer);
            return;
        }
        if (sourceRef.startsWith("claim:")) {
            UUID claimId = parseRef(sourceRef, "claim:");
            answerPatchRepository.findByProject(projectId).stream()
                    .filter(patch -> patch.claims().stream().anyMatch(claim -> claimId.equals(claim.id())))
                    .findFirst()
                    .ifPresent(this::indexPatch);
            return;
        }
        throw new IllegalArgumentException("Unsupported retrieval sourceRef: " + sourceRef);
    }

    private UUID parseRef(String sourceRef, String prefix) {
        try {
            return UUID.fromString(sourceRef.substring(prefix.length()));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Malformed retrieval sourceRef: " + sourceRef, ex);
        }
    }

    /** Incrementally projects one canonical Node without touching other rows. */
    @Transactional
    public void indexNode(Node node) {
        if (node == null) {
            return;
        }
        UUID projectId = node.projectId();
        entryRepository.deleteSourcePrefix(projectId, "resource-chunk:" + node.id() + ":");
        entryRepository.deleteSource(projectId, "node:" + node.id());
        if (!sourcePolicy.allow(node)) {
            return;
        }
        if (node.kind() == NodeKind.RESOURCE) {
            projectResource(projectId, node);
        } else {
            projectNode(projectId, node);
        }
    }

    /**
     * Refreshes only sources whose membership can change when a route starts
     * from the supplied canonical prefix. This updates metadata in place and
     * therefore preserves content hashes and valid embeddings.
     */
    @Override
    @Transactional
    public void refreshRouteAffectedSources(UUID projectId,
                                            UUID routeId,
                                            Collection<UUID> lineageRootNodeIds) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalArgumentException("Route not found: " + routeId));
        for (Node node : nodeRepository.findDescendants(projectId, lineageRootNodeIds)) {
            if (routeHistoryResolver.belongsToRoute(route, node.id())) {
                refreshNodeRouteProvenance(projectId, node);
            }
        }
    }

    @Override
    @Transactional
    public void refreshNodeRouteProvenance(UUID projectId, Collection<UUID> nodeIds) {
        if (nodeIds == null) {
            return;
        }
        for (UUID nodeId : nodeIds) {
            nodeRepository.findById(nodeId)
                    .filter(node -> projectId.equals(node.projectId()))
                    .ifPresent(node -> refreshNodeRouteProvenance(projectId, node));
        }
    }

    private void refreshNodeRouteProvenance(UUID projectId, Node node) {
        List<String> routeIds = originRouteIds(projectId, node.id());
        UUID routeId = routeIdFor(routeIds);
        boolean workspaceScoped = routeIds.isEmpty();
        if (node.kind() == NodeKind.RESOURCE) {
            entryRepository.updateRouteProvenancePrefix(
                    projectId, "resource-chunk:" + node.id() + ":", routeId, routeIds, workspaceScoped);
        } else {
            entryRepository.updateRouteProvenance(
                    projectId, "node:" + node.id(), routeId, routeIds, workspaceScoped);
        }
    }

    /** Incrementally projects one immutable canonical Answer. */
    @Transactional
    public void indexAnswer(Answer answer) {
        if (answer == null) {
            return;
        }
        entryRepository.deleteSource(answer.projectId(), "answer:" + answer.id());
        projectAnswer(answer);
    }

    /** Incrementally projects all claims in one immutable AnswerPatch. */
    @Transactional
    public void indexPatch(AnswerPatch patch) {
        if (patch == null) {
            return;
        }
        int claimOrdinal = 0;
        for (Claim claim : patch.claims()) {
            UUID claimId = claim.id() == null
                    ? UUID.nameUUIDFromBytes((patch.id() + ":claim:" + claimOrdinal)
                            .getBytes(StandardCharsets.UTF_8))
                    : claim.id();
            entryRepository.deleteSource(patch.projectId(), "claim:" + claimId);
            claimOrdinal++;
        }
        projectPatch(patch);
    }

    /** Retraction is durable in the projection; the canonical source remains. */
    @Transactional
    public void retractSource(UUID projectId, String sourceRef) {
        entryRepository.retractSource(projectId, sourceRef);
    }

    private void projectNode(UUID projectId, Node node) {
        String text = joinText(node.question(), node.purpose(), node.contentText());
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nodeId", node.id().toString());
        metadata.put("kind", node.kind().code());
        metadata.put("subtype", node.subtype() == null ? "" : node.subtype());
        addRouteProvenance(projectId, node.id(), metadata);
        if (text.isBlank() || !sourcePolicy.allowText(text)
                || !sourcePolicy.allowMetadata(metadata)) {
            return;
        }
        entryRepository.upsert(newEntry(projectId, routeIdFor(metadata), RetrievalSourceKind.NODE,
                node.id(), "node:" + node.id(), RetrievalScope.PROJECT,
                nodeAuthority(node), text, metadata,
                node.retractedAt()));
    }

    private void projectResource(UUID projectId, Node node) {
        String text = node.contentText();
        if (text == null || text.isBlank() || !sourcePolicy.allowText(text)) {
            return;
        }
        for (ResourceChunker.Chunk chunk : chunker.chunk(text)) {
            String sourceRef = "resource-chunk:" + node.id() + ":" + chunk.index();
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("resourceId", node.id().toString());
            metadata.put("chunk", chunk.index());
            metadata.put("lineRange", List.of(chunk.startChar(), chunk.endChar()));
            metadata.put("subtype", node.subtype());
            addRouteProvenance(projectId, node.id(), metadata);
            Object page = node.content().get("page");
            if (page != null) {
                metadata.put("page", page);
            }
            Object url = node.content().get("url");
            if (url instanceof String value && !value.isBlank()) {
                metadata.put("url", value);
            }
            if (!sourcePolicy.allowMetadata(metadata)) {
                continue;
            }
            entryRepository.upsert(newEntry(projectId, routeIdFor(metadata), RetrievalSourceKind.RESOURCE_CHUNK,
                    node.id(), sourceRef, RetrievalScope.RESOURCE,
                    MemoryAuthority.EXTERNAL_EVIDENCE, chunk.content(), metadata,
                    node.retractedAt()));
        }
    }

    private void projectAnswer(Answer answer) {
        String text = joinText(answer.freeText(), answer.selectedOptionId());
        Map<String, Object> metadata = Map.of(
                "nodeId", answer.nodeId().toString(), "routeId", answer.routeId().toString());
        if (text.isBlank() || !sourcePolicy.allowText(text)
                || !sourcePolicy.allowMetadata(metadata)) {
            return;
        }
        entryRepository.upsert(newEntry(answer.projectId(), answer.routeId(), RetrievalSourceKind.ANSWER,
                answer.id(), "answer:" + answer.id(), RetrievalScope.ROUTE,
                MemoryAuthority.USER_AUTHORED, text, metadata, null));
    }

    private void projectPatch(AnswerPatch patch) {
        int claimOrdinal = 0;
        for (Claim claim : patch.claims()) {
            if (claim.text() == null || claim.text().isBlank()
                    || !sourcePolicy.allowText(claim.text())) {
                claimOrdinal++;
                continue;
            }
            UUID claimId = claim.id() == null
                    ? UUID.nameUUIDFromBytes((patch.id() + ":claim:" + claimOrdinal)
                            .getBytes(StandardCharsets.UTF_8))
                    : claim.id();
            String sourceRef = "claim:" + claimId;
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("claimId", claimId.toString());
            metadata.put("sourceNodeId", patch.sourceNodeId().toString());
            metadata.put("sourceAnswerId", patch.sourceAnswerId().toString());
            metadata.put("kind", claim.kind() == null ? "other" : claim.kind().code());
            if (!sourcePolicy.allowMetadata(metadata)) {
                claimOrdinal++;
                continue;
            }
            entryRepository.upsert(newEntry(patch.projectId(), patch.routeId(), RetrievalSourceKind.CLAIM,
                    claimId, sourceRef, RetrievalScope.ROUTE,
                    claimAuthority(claim.status()), claim.text(), metadata, null));
            claimOrdinal++;
        }
    }

    private RetrievalEntry newEntry(UUID projectId, UUID routeId, RetrievalSourceKind kind,
                                    UUID sourceId, String sourceRef, RetrievalScope scope,
                                    MemoryAuthority authority, String content,
                                    Map<String, Object> metadata, Instant retractedAt) {
        String normalized = content == null ? "" : content.strip();
        return new RetrievalEntry(
                UUID.nameUUIDFromBytes((projectId + ":" + sourceRef).getBytes(StandardCharsets.UTF_8)),
                projectId, routeId, kind, sourceId, sourceRef, scope, authority,
                normalized, Hashes.sha256Hex(normalized), metadata, null, null,
                retractedAt == null ? "PENDING" : "UNAVAILABLE", retractedAt);
    }

    /**
     * Route membership is projection metadata, not canonical Node identity: a
     * shared Node may be material in several routes. The resolver owns the
     * route lineage semantics, while floating workspace nodes intentionally
     * retain an empty provenance set.
     */
    private void addRouteProvenance(UUID projectId, UUID nodeId,
                                     Map<String, Object> metadata) {
        List<String> routeIds = originRouteIds(projectId, nodeId);
        metadata.put("originRouteIds", routeIds);
        metadata.put("workspaceScoped", routeIds.isEmpty());
    }

    private List<String> originRouteIds(UUID projectId, UUID nodeId) {
        return routeRepository.findByProject(projectId).stream()
                .filter(route -> route.tipNodeId() != null
                        && routeHistoryResolver.belongsToRoute(route, nodeId))
                .map(Route::id)
                .map(UUID::toString)
                .toList();
    }

    private UUID routeIdFor(Map<String, Object> metadata) {
        Object raw = metadata.get("originRouteIds");
        if (!(raw instanceof List<?> routeIds) || routeIds.size() != 1) {
            return null;
        }
        return routeIdFor(routeIds);
    }

    private UUID routeIdFor(List<?> routeIds) {
        if (routeIds == null || routeIds.size() != 1) {
            return null;
        }
        try {
            return UUID.fromString(String.valueOf(routeIds.get(0)));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private MemoryAuthority nodeAuthority(Node node) {
        if (node.authorKind() == NodeAuthorKind.USER) {
            return node.knowledgeStatus() == KnowledgeStatus.CONFIRMED
                    ? MemoryAuthority.CONFIRMED : MemoryAuthority.USER_AUTHORED;
        }
        if (node.knowledgeStatus() == KnowledgeStatus.CONFIRMED) {
            return MemoryAuthority.CONFIRMED;
        }
        return node.knowledgeStatus() == KnowledgeStatus.CHALLENGED
                ? MemoryAuthority.UNRESOLVED : MemoryAuthority.DERIVED;
    }

    private MemoryAuthority claimAuthority(ClaimStatus status) {
        if (status == null) {
            return MemoryAuthority.DERIVED;
        }
        return switch (status) {
            case CONFIRMED -> MemoryAuthority.CONFIRMED;
            case ASSUMED -> MemoryAuthority.ASSUMED;
            case UNRESOLVED -> MemoryAuthority.UNRESOLVED;
            case REJECTED -> MemoryAuthority.REJECTED;
        };
    }

    private String joinText(String... values) {
        List<String> present = new ArrayList<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                present.add(value.strip());
            }
        }
        return String.join("\n", present);
    }
}
