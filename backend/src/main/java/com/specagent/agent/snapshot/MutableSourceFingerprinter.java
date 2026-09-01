package com.specagent.agent.snapshot;

import com.specagent.common.Hashes;
import com.specagent.common.Json;
import com.specagent.node.Node;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Derives the canonical fingerprint set for the mutable sources that were
 * model-visible inside one frozen projection.
 *
 * <p>Fingerprinted sources:
 * <ul>
 *   <li>lineage nodes -- canonical id + semantic body hash (question/content +
 *       options + kind/subtype + allowFreeAnswer)</li>
 *   <li>related nodes -- same body hash when present as first-class context</li>
 * </ul>
 * Non-fingerprinted: immutable answers/patches, capability observations
 * (bounded by project, not by snapshot), route labels that are not part of
 * model payload identity in this phase.
 */
@Component
public class MutableSourceFingerprinter {

    private final Json json;

    public MutableSourceFingerprinter(Json json) {
        this.json = json;
    }

    public List<MutableSourceFingerprint> fingerprintsFor(List<Node> lineageNodes,
                                                          List<Node> relatedNodes) {
        List<MutableSourceFingerprint> out = new ArrayList<>();
        for (Node node : lineageNodes) {
            out.add(new MutableSourceFingerprint("NODE", node.id(), nodeBodyHash(node)));
        }
        for (Node node : relatedNodes) {
            out.add(new MutableSourceFingerprint("RELATED_NODE", node.id(), nodeBodyHash(node)));
        }
        out.sort(Comparator.comparing(MutableSourceFingerprint::sourceType)
                .thenComparing(f -> f.sourceId().toString()));
        return List.copyOf(out);
    }

    public String nodeBodyHash(Node node) {
        Map<String, Object> canonical = new java.util.LinkedHashMap<>();
        canonical.put("kind", node.kind() == null ? null : node.kind().code());
        canonical.put("subtype", node.subtype());
        canonical.put("question", node.question());
        canonical.put("content", node.content());
        canonical.put("options", node.options());
        canonical.put("allowFreeAnswer", node.allowFreeAnswer());
        canonical.put("authorKind", node.authorKind() == null ? null : node.authorKind().code());
        canonical.put("knowledgeStatus", node.knowledgeStatus() == null ? null : node.knowledgeStatus().code());
        return Hashes.sha256Hex(json.write(canonical));
    }

    public String fingerprintSetHash(List<MutableSourceFingerprint> fingerprints) {
        StringBuilder sb = new StringBuilder();
        for (MutableSourceFingerprint f : fingerprints) {
            sb.append(f.sourceType()).append(':').append(f.sourceId()).append(':').append(f.contentHash()).append('\n');
        }
        return Hashes.sha256Hex(sb.toString());
    }
}
