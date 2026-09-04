package com.specagent.trace;

import com.specagent.agent.contract.AgentContracts;
import com.specagent.agent.contract.AgentRequestEnvelope;
import com.specagent.agent.contract.AgentInputSnapshot;
import com.specagent.agent.contract.ClaimView;
import com.specagent.agent.contract.LineageEntry;
import com.specagent.agent.contract.RelatedNodeRef;
import com.specagent.common.Hashes;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diagnostic-only fingerprint of decision-relevant semantics.
 *
 * <p>Runtime identities, snapshot ids and ordering noise are intentionally
 * excluded.  The fingerprint is never put into a model request; it only
 * supports repeated-attempt stability analysis.
 */
public final class SemanticFingerprint {

    private SemanticFingerprint() {
    }

    public static String forRequest(AgentRequestEnvelope request) {
        Map<String, Object> semantic = new LinkedHashMap<>();
        semantic.put("event", Map.of(
                "kind", request.event().kind(),
                "freeText", request.event().freeText() == null
                        ? "" : request.event().freeText(),
                "selectedOptionPresent", request.event().selectedOptionId() != null));
        semantic.putAll(snapshotSemantics(request.snapshot()));
        return Hashes.sha256Hex(AgentContracts.write(semantic));
    }

    /** Fingerprint for the post-state semantic projection, excluding runtime ids. */
    public static String forSnapshot(AgentInputSnapshot snapshot) {
        return Hashes.sha256Hex(AgentContracts.write(snapshotSemantics(snapshot)));
    }

    private static Map<String, Object> snapshotSemantics(AgentInputSnapshot snapshot) {
        Map<String, Object> semantic = new LinkedHashMap<>();
        Map<String, Object> route = new LinkedHashMap<>();
        route.put("label", snapshot.routeContext() == null
                ? "" : snapshot.routeContext().label());
        route.put("routePresent", snapshot.routeContext() != null
                && snapshot.routeContext().routeId() != null);
        route.put("tipPresent", snapshot.routeContext() != null
                && snapshot.routeContext().tipNodeId() != null);
        semantic.put("routeContext", route);
        semantic.put("metadata", Map.of(
                "projectTitle", snapshot.metadata() == null
                        || snapshot.metadata().projectTitle() == null
                        ? "" : snapshot.metadata().projectTitle()));
        semantic.put("autonomyMode", snapshot.autonomy() == null
                || snapshot.autonomy().mode() == null ? "" : snapshot.autonomy().mode());
        semantic.put("allowedSourceRefKinds", snapshot.allowedSourceRefs().stream()
                .map(SemanticFingerprint::sourceRefKind)
                .sorted().toList());
        semantic.put("claims", snapshot.effectiveClaims().stream()
                .map(SemanticFingerprint::claim)
                .sorted(Comparator.comparing(value -> String.valueOf(value)))
                .toList());
        semantic.put("lineage", snapshot.lineage().stream()
                .map(SemanticFingerprint::lineage)
                .toList());
        semantic.put("capabilities", snapshot.availableCapabilities().stream()
                .map(capability -> Map.of(
                        "id", capability.id(),
                        "version", capability.version(),
                        "description", capability.description(),
                        "readOnly", capability.readOnly(),
                        "sideEffectClass", capability.sideEffectClass()))
                .sorted(Comparator.comparing(value -> String.valueOf(value)))
                .toList());
        semantic.put("capabilityResults", snapshot.capabilityResults().stream()
                .map(result -> Map.of(
                        "capabilityId", result.capabilityId(),
                        "status", result.status(),
                        "content", result.content(),
                        "sourceRefKinds", result.sourceRefs().stream()
                                .map(SemanticFingerprint::sourceRefKind)
                                .sorted().toList()))
                .sorted(Comparator.comparing(value -> String.valueOf(value)))
                .toList());
        semantic.put("relations", snapshot.relations().stream()
                .map(relation -> Map.of(
                        "type", relation.relationType(),
                        "direction", direction(snapshot.anchorNodeId(),
                                relation.sourceNodeId(), relation.targetNodeId())))
                .sorted(Comparator.comparing(value -> String.valueOf(value)))
                .toList());
        semantic.put("relatedNodes", snapshot.relatedNodes().stream()
                .map(SemanticFingerprint::relatedNode)
                .sorted(Comparator.comparing(value -> String.valueOf(value)))
                .toList());
        return semantic;
    }

    private static String sourceRefKind(String sourceRef) {
        if (sourceRef == null || sourceRef.isBlank()) {
            return "";
        }
        int separator = sourceRef.indexOf(':');
        return separator < 0 ? "opaque" : sourceRef.substring(0, separator);
    }

    private static Map<String, Object> claim(ClaimView claim) {
        return Map.of("kind", claim.kind(), "status", claim.status(), "text", claim.text());
    }

    private static Map<String, Object> lineage(LineageEntry entry) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("kind", entry.node().kind());
        value.put("text", entry.node().body().text());
        value.put("answered", entry.answer() != null);
        value.put("answerText", entry.answer() == null || entry.answer().freeText() == null
                ? "" : entry.answer().freeText());
        value.put("patches", entry.patches().stream()
                .flatMap(patch -> patch.claims().stream())
                .map(SemanticFingerprint::claim)
                .sorted(Comparator.comparing(item -> String.valueOf(item)))
                .toList());
        return value;
    }

    private static Map<String, Object> relatedNode(RelatedNodeRef related) {
        return Map.of(
                "relationType", related.relationType(),
                "direction", related.direction(),
                "kind", related.node().kind(),
                "text", related.node().body().text());
    }

    private static String direction(java.util.UUID anchor,
                                    java.util.UUID source,
                                    java.util.UUID target) {
        if (anchor != null && anchor.equals(source)) {
            return "OUTGOING";
        }
        if (anchor != null && anchor.equals(target)) {
            return "INCOMING";
        }
        return "UNKNOWN";
    }
}
