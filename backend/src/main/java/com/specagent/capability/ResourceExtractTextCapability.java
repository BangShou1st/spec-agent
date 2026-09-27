package com.specagent.capability;

import com.specagent.workspace.node.Node;
import com.specagent.workspace.node.NodeKind;
import com.specagent.workspace.node.NodeRepository;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:ResourceExtractTextCapability.java
 *
 * 用途:内置只读能力,从 RESOURCE 节点提取有界且保留溯源的文本摘录。
 *
 * 大段内容永远不会被完整注入提示词——结果只携带有界摘录加截断元数据,
 * 以及源节点引用,后续决策周期若需要更多内容可再次检索。
 * 结果属于外部来源证据,永远不会被自动确认为图谱事实。
 */
@Component
public class ResourceExtractTextCapability implements InternalCapabilityAdapter {

    /** 摘录长度上限;完整内容仍保留在资源节点中。 */
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
