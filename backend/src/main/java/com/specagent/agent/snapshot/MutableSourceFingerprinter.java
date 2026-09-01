package com.specagent.agent.snapshot;

import com.specagent.agent.contract.NodeView;
import com.specagent.common.Hashes;
import com.specagent.common.Json;
import com.specagent.node.Node;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Derives canonical hashes for mutable model-visible node sources.
 *
 * <p>New frozen rows keep the richer persisted source fingerprint used by P1.
 * Legacy frozen rows that predate persisted fingerprints can still derive a
 * safe stale precondition from their immutable {@code AgentInputSnapshot}
 * payload via {@link #modelVisibleNodeHash(NodeView)}. That legacy derivation
 * hashes exactly the node fields the model saw, never current live state.
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

    /**
     * Rich P1 fingerprint for rows created by the current projection schema.
     * Kept stable for compatibility with already-frozen rows.
     */
    public String nodeBodyHash(Node node) {
        Map<String, Object> canonical = new LinkedHashMap<>();
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

    /**
     * Hashes only the model-visible NodeView semantics. Used to derive stale
     * preconditions for legacy frozen projections whose source fingerprint
     * column is empty. The live overload below builds the same canonical shape.
     */
    public String modelVisibleNodeHash(NodeView view) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("kind", view.kind());
        canonical.put("text", view.body() == null ? null : view.body().text());
        canonical.put("options", view.body() == null ? List.of() : view.body().options().stream()
                .map(option -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", option.id());
                    item.put("label", option.label());
                    return item;
                })
                .toList());
        canonical.put("acceptsFreeText", view.body() != null && view.body().acceptsFreeText());
        return Hashes.sha256Hex(json.write(canonical));
    }

    public String modelVisibleNodeHash(Node node) {
        Map<String, Object> canonical = new LinkedHashMap<>();
        canonical.put("kind", node.kind() == null ? null : node.kind().code());
        canonical.put("text", node.question() != null ? node.question() : node.contentText());
        canonical.put("options", node.options().stream()
                .map(option -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", option.id());
                    item.put("label", option.label());
                    return item;
                })
                .toList());
        canonical.put("acceptsFreeText", node.allowFreeAnswer());
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
