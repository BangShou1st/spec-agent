package com.specagent.skill.runtime;

import com.specagent.capability.CapabilityDescriptor;
import com.specagent.capability.CapabilityInvocation;
import com.specagent.capability.CapabilityResult;
import com.specagent.capability.InternalCapabilityAdapter;
import com.specagent.capability.SideEffectClass;
import com.specagent.skill.runtime.SkillActivationService.ActivatedSkill;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 文件名:SkillActivateHostTool.java
 *
 * 用途:Host Function Tool {@code skill.activate}:为当前运行激活一个已安装、
 * 已启用且可见的 Skill,返回有界的指令与随包资源清单。它是调用 Skill Runtime
 * 的 Host Function Tool —— Skill 是过程性知识,不是另一个 Agent,默认也不是
 * 可执行的 Capability。
 *
 * 只读、NONE 副作用等级:不执行任何脚本、不安装任何依赖、不产生持久的
 * 外部变更。激活的溯源信息由 {@link SkillActivationService} 持久化。
 */
@Component
public class SkillActivateHostTool implements InternalCapabilityAdapter {

    public static final String CAPABILITY_ID = "skill.activate";

    private final SkillActivationService activationService;

    public SkillActivateHostTool(SkillActivationService activationService) {
        this.activationService = activationService;
    }

    @Override
    public CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor(
                CAPABILITY_ID,
                "1",
                "激活一个已安装且启用的 Skill：返回有界的指令与资源清单（不执行任何脚本）",
                Map.of("skillId", Map.of("type", "string", "required", true)),
                Map.of("skillId", Map.of("type", "string"),
                        "name", Map.of("type", "string"),
                        "versionNo", Map.of("type", "integer"),
                        "instructions", Map.of("type", "string"),
                        "resources", Map.of("type", "array")),
                true,
                SideEffectClass.NONE,
                List.of(),
                List.of());
    }

    @Override
    public CapabilityResult invoke(CapabilityInvocation invocation) {
        if (invocation.projectId() == null) {
            return CapabilityResult.failed(invocation.invocationId(),
                    invocation.invocationKey(), CAPABILITY_ID,
                    "skill.activate requires a project context");
        }
        Object rawId = invocation.arguments().get("skillId");
        if (!(rawId instanceof String skillId) || skillId.isBlank()) {
            return CapabilityResult.failed(invocation.invocationId(),
                    invocation.invocationKey(), CAPABILITY_ID,
                    "arguments.skillId must be a non-blank string");
        }
        try {
            ActivatedSkill activated = activationService.activate(
                    invocation.projectId(), invocation.runId(), skillId);
            return new CapabilityResult(
                    invocation.invocationId(), invocation.invocationKey(), CAPABILITY_ID,
                    CapabilityResult.Status.SUCCEEDED,
                    Map.of(
                            "skillId", activated.skillId(),
                            "name", activated.name(),
                            "versionNo", activated.versionNo(),
                            "contentHash", activated.contentHash(),
                            "instructions", activated.instructions(),
                            "resources", activated.resources()),
                    List.of(),
                    Map.of("kind", "SKILL_ACTIVATION",
                            "contentHash", activated.contentHash(),
                            "versionId", activated.versionId().toString()),
                    List.of());
        } catch (SkillNotVisibleException ex) {
            return CapabilityResult.failed(invocation.invocationId(),
                    invocation.invocationKey(), CAPABILITY_ID, ex.getMessage());
        } catch (RuntimeException ex) {
            return CapabilityResult.failed(invocation.invocationId(),
                    invocation.invocationKey(), CAPABILITY_ID,
                    "Skill activate failed: " + ex.getClass().getSimpleName());
        }
    }
}