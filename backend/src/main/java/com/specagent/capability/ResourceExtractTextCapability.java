package com.specagent.capability;

import com.specagent.node.Node;
import com.specagent.node.NodeKind;
import com.specagent.node.NodeRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Internal read-only capability: extract a bounded, provenance-preserving
 * text excerpt from a RESOURCE node.
 *
 * <p>Large contents are never fully injected into prompts — the result
 * carries a bounded excerpt plus truncation metadata, and the source node
 * ref so later cycles can retrieve more if needed. The result is external
 * source evidence, never auto-confirmed graph truth.
 */
@Component
public class ResourceExtractTextCapability implements InternalCapabilityAdapter {

    /** Bounded excerpt length; full content stays in the resource node. */
    static final int MAX_EXCERPT_CHARS = 2000;

    public static final String CAPABILITY_ID = "resource.extract_text";

    private final NodeRepository nodeRepository;

    public ResourceExtractTextCapability(NodeRepository nodeRepository) {
        this.nodeRepository = nodeRepository;
    }

    @Override
    public CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                CAPABILITY_ID,
                "1",
                "从资源节点提取有界文本摘录（保留来源引用，不注入全文）",
                Map.of("nodeRef", Map.of("type", "string", "required", true)),
                Map.of("excerpt", Map.of("type", "string"), "truncated", Map.of("type", "boolean")),
                true,
                SideEffectClass.NONE,
                List.of(),
                List.of("RESOURCE:FILE", "RESOURCE:URL", "RESOURCE:TEXT"));
    }

    @Override
    public CapabilityResult invoke(CapabilityInvocation invocation) {
        Object nodeRef = invocation.arguments().get("nodeRef");
        if (!(nodeRef instanceof String ref) || !ref.startsWith("node:")) {
            return CapabilityResult.failed(invocation.invocationId(), invocation.invocationKey(),
                    CAPABILITY_ID, "arguments.nodeRef must be a node: reference");
        }
        UUID nodeId;
        try {
            nodeId = UUID.fromString(ref.substring(5));
        } catch (IllegalArgumentException ex) {
            return CapabilityResult.failed(invocation.invocationId(), invocation.invocationKey(),
                    CAPABILITY_ID, "arguments.nodeRef is not a valid node reference");
        }

        Node node = nodeRepository.findById(nodeId).orElse(null);
        if (node == null || !node.projectId().equals(invocation.projectId())) {
            return CapabilityResult.failed(invocation.invocationId(), invocation.invocationKey(),
                    CAPABILITY_ID, "Resource node not found in project: " + nodeId);
        }
        if (node.kind() != NodeKind.RESOURCE) {
            return CapabilityResult.failed(invocation.invocationId(), invocation.invocationKey(),
                    CAPABILITY_ID, "Node is not a RESOURCE node: " + nodeId);
        }

        String text = node.contentText() == null ? "" : node.contentText();
        boolean truncated = text.length() > MAX_EXCERPT_CHARS;
        String excerpt = truncated ? text.substring(0, MAX_EXCERPT_CHARS) : text;
        if (excerpt.isBlank()) {
            return CapabilityResult.failed(invocation.invocationId(), invocation.invocationKey(),
                    CAPABILITY_ID, "Resource node carries no text content: " + nodeId);
        }

        Map<String, Object> content = new LinkedHashMap<>();
        content.put("excerpt", excerpt);
        content.put("truncated", truncated);
        content.put("totalChars", text.length());
        Object url = node.content().get("url");
        if (url instanceof String value && !value.isBlank()) {
            content.put("url", value);
        }

        return new CapabilityResult(
                invocation.invocationId(),
                invocation.invocationKey(),
                CAPABILITY_ID,
                CapabilityResult.Status.SUCCEEDED,
                content,
                List.of("node:" + nodeId),
                Map.of("kind", "EXTERNAL_SOURCE_EVIDENCE",
                       "subtype", node.subtype(),
                       "nodeCreatedAt", node.createdAt().toString()),
                List.of());
    }
}
