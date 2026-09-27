package com.specagent.agent.trace;

import com.specagent.agent.protocol.AgentContracts;
import com.specagent.agent.protocol.AgentRequestEnvelope;
import com.specagent.agent.protocol.AgentInputSnapshot;
import com.specagent.agent.protocol.ClaimView;
import com.specagent.agent.protocol.LineageEntry;
import com.specagent.agent.protocol.RelatedNodeRef;
import com.specagent.common.Hashes;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文件名:SemanticFingerprint.java
 *
 * 用途:仅用于诊断的"决策相关语义"指纹(sha256),比较同一语义下
 * 不同尝试的结果是否稳定。
 *
 * 约束:刻意排除 Runtime 身份标识、快照 id 和顺序噪声;指纹绝不
 * 进入模型请求,只用于重复尝试的稳定性分析。
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

    /** 后状态语义投影的指纹,排除 Runtime id。 */
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
