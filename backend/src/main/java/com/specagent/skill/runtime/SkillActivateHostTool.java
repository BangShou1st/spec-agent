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
 * Host Function Tool {@code skill.activate}: activates an installed, enabled,
 * visible Skill for the current run, returning bounded instructions plus a
 * bundled resource inventory. It is a Host Function Tool that calls the Skill
 * Runtime — a Skill is procedural knowledge, not another agent and not (by
 * default) an executable Capability.
 *
 * <p>Read-only, NONE side-effect class: no scripts execute, no dependency is
 * installed, no durable external mutation occurs. Activation provenance is
 * persisted by {@link SkillActivationService}.
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