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
import com.specagent.patch.AnswerPatch;
import com.specagent.patch.AnswerPatchRepository;
import com.specagent.patch.Claim;
import com.specagent.patch.ClaimStatus;
import com.specagent.retrieval.api.MemoryAuthority;
import com.specagent.retrieval.api.RetrievalScope;
import com.specagent.retrieval.api.RetrievalSourceKind;
import com.specagent.retrieval.persistence.RetrievalEntry;
import com.specagent.retrieval.persistence.RetrievalEntryRepository;
import com.specagent.route.Route;
import com.specagent.route.RouteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Rebuilds retrieval rows from canonical Runtime records only. */
@Service
public class RetrievalSourceProjector {

    private final NodeRepository nodeRepository;
    private final AnswerRepository answerRepository;
    private final AnswerPatchRepository answerPatchRepository;
    private final RouteRepository routeRepository;
    private final RetrievalEntryRepository entryRepository;
    private final Json json;
    private final ResourceChunker chunker = new ResourceChunker();
    private final RetrievalSourcePolicy sourcePolicy = new RetrievalSourcePolicy();

    public RetrievalSourceProjector(NodeRepository nodeRepository,
                                    AnswerRepository answerRepository,
                                    AnswerPatchRepository answerPatchRepository,
                                    RouteRepository routeRepository,
                                    RetrievalEntryRepository entryRepository,
                                    Json json) {
        this.nodeRepository = nodeRepository;
        this.answerRepository = answerRepository;
        this.answerPatchRepository = answerPatchRepository;
        this.routeRepository = routeRepository;
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
            if (!sourcePolicy.allow(node)) {
                continue;
            }
            if (node.kind() == NodeKind.RESOURCE) {
                projectResource(projectId, node);
            } else {
                projectNode(projectId, node);
            }
        }
        for (Answer answer : answerRepository.findByProject(projectId)) {
            projectAnswer(answer);
        }
        for (AnswerPatch patch : answerPatchRepository.findByProject(projectId)) {
            projectPatch(patch);
        }
    }

    private void projectNode(UUID projectId, Node node) {
        String text = joinText(node.question(), node.purpose(), node.contentText());
        if (text.isBlank() || !sourcePolicy.allowText(text)) {
            return;
        }
        entryRepository.upsert(newEntry(projectId, null, RetrievalSourceKind.NODE,
                node.id(), "node:" + node.id(), RetrievalScope.PROJECT,
                nodeAuthority(node), text, Map.of(
                        "nodeId", node.id().toString(),
                        "kind", node.kind().code(),
                        "subtype", node.subtype() == null ? "" : node.subtype()),
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
            Object page = node.content().get("page");
            if (page != null) {
                metadata.put("page", page);
            }
            Object url = node.content().get("url");
            if (url instanceof String value && !value.isBlank()) {
                metadata.put("url", value);
            }
            entryRepository.upsert(newEntry(projectId, null, RetrievalSourceKind.RESOURCE_CHUNK,
                    node.id(), sourceRef, RetrievalScope.RESOURCE,
                    MemoryAuthority.EXTERNAL_EVIDENCE, chunk.content(), metadata,
                    node.retractedAt()));
        }
    }

    private void projectAnswer(Answer answer) {
        String text = joinText(answer.freeText(), answer.selectedOptionId());
        if (text.isBlank()) {
            return;
        }
        entryRepository.upsert(newEntry(answer.projectId(), answer.routeId(), RetrievalSourceKind.ANSWER,
                answer.id(), "answer:" + answer.id(), RetrievalScope.ROUTE,
                MemoryAuthority.USER_AUTHORED, text,
                Map.of("nodeId", answer.nodeId().toString(), "routeId", answer.routeId().toString()), null));
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
