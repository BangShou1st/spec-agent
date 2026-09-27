package com.specagent.agent.snapshot;

import com.specagent.agent.protocol.NodeView;
import com.specagent.common.Hashes;
import com.specagent.common.Json;
import com.specagent.workspace.node.Node;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文件名:MutableSourceFingerprinter.java
 *
 * 用途:为"模型可见的易变节点来源"推导规范化哈希。
 *
 * 新的冻结行保留 P1 使用的、更丰富的持久化来源指纹。早于持久化指纹的
 * 遗留冻结行,仍可通过 {@link #modelVisibleNodeHash(NodeView)} 从其不可变的
 * {@code AgentInputSnapshot} payload 推导出安全的 stale 前置条件。该遗留
 * 推导只对模型当时看到的节点字段做哈希,绝不使用当前活状态。
 *
 * 协作:被 AgentInputSnapshotBuilder(冻结时生成指纹)和
 * StaleContextChecker(执行前重算并比对)使用。
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
     * 当前投影 schema 创建的行所用的丰富 P1 指纹。
     * 为兼容已冻结的历史行而保持算法不变。
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
     * 只对模型可见的 NodeView 语义做哈希。用于为 source fingerprint 列为空的
     * 遗留冻结投影推导 stale 前置条件。下面对活节点的重载构建同样的规范化形状。
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
