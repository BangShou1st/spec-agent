package com.specagent.skill.runtime;

import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.InternalCapabilityAdapter;
import com.specagent.capability.SideEffectClass;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:SkillReadResourceHostTool.java
 *
 * 用途:Host Function Tool {@code skill.read_resource}:读取一个已激活
 * Skill 版本的指定资源,执行严格的路径包含检查并附带溯源信息。只读、NONE
 * 副作用等级;二进制资源一律拒绝(一期只提供文本资源)。
 */
@Component
public class SkillReadResourceHostTool implements InternalCapabilityAdapter {

    public static final String CAPABILITY_ID = "skill.read_resource";

    private final SkillResourceService resourceService;

    public SkillReadResourceHostTool(SkillResourceService resourceService) {
        this.resourceService = resourceService;
    }

    @Override
    public CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                CAPABILITY_ID,
                "1",
                "读取已激活 Skill 的一个文本资源（路径包含检查，返回有界内容与出处）",
                Map.of(
                        "versionId", Map.of("type", "string", "required", true),
                        "path", Map.of("type", "string", "required", true)),
                Map.of("relativePath", Map.of("type", "string"),
                        "content", Map.of("type", "string"),
                        "truncated", Map.of("type", "boolean"),
                        "sha256", Map.of("type", "string")),
                true,
                SideEffectClass.NONE,
                List.of(),
                List.of());
    }

    @Override
    public CapabilityResult invoke(CapabilityInvocation invocation) {
        Object rawVersion = invocation.arguments().get("versionId");
        Object rawPath = invocation.arguments().get("path");
        if (!(rawVersion instanceof String versionText) || versionText.isBlank()) {
            return CapabilityResult.failed(invocation.invocationId(),
                    invocation.invocationKey(), CAPABILITY_ID,
                    "arguments.versionId must be a non-blank version id");
        }
        if (!(rawPath instanceof String path) || path.isBlank()) {
            return CapabilityResult.failed(invocation.invocationId(),
                    invocation.invocationKey(), CAPABILITY_ID,
                    "arguments.path must be a non-blank resource path");
        }
        UUID versionId;
        try {
            versionId = UUID.fromString(versionText);
        } catch (IllegalArgumentException ex) {
            return CapabilityResult.failed(invocation.invocationId(),
                    invocation.invocationKey(), CAPABILITY_ID,
                    "arguments.versionId is not a valid version id");
        }
        try {
            SkillResourceService.ResourceRead read = resourceService.readResource(versionId, path);
            return new CapabilityResult(
                    invocation.invocationId(), invocation.invocationKey(), CAPABILITY_ID,
                    CapabilityResult.Status.SUCCEEDED,
                    Map.of(
                            "relativePath", read.relativePath(),
                            "content", read.content(),
                            "truncated", read.truncated(),
                            "totalChars", read.totalChars(),
                            "sha256", read.sha256()),
                    List.of(),
                    Map.of("kind", "SKILL_RESOURCE",
                            "versionId", read.versionId(),
                            "sha256", read.sha256()),
                    List.of());
        } catch (SkillResourceRejectedException ex) {
            return CapabilityResult.failed(invocation.invocationId(),
                    invocation.invocationKey(), CAPABILITY_ID, ex.getMessage());
        } catch (RuntimeException ex) {
            return CapabilityResult.failed(invocation.invocationId(),
                    invocation.invocationKey(), CAPABILITY_ID,
                    "Skill resource read failed: " + ex.getClass().getSimpleName());
        }
    }
}