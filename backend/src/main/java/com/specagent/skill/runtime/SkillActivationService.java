package com.specagent.skill.runtime;

import com.specagent.skill.config.SkillProperties;
import com.specagent.skill.domain.Skill;
import com.specagent.skill.domain.SkillVersion;
import com.specagent.skill.importing.SkillImportException;
import com.specagent.skill.persistence.SkillRepository;
import com.specagent.skill.registry.SkillQueryService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 文件名:SkillActivationService.java
 *
 * 用途:Skill 激活服务。激活被限定在当前 run/续跑上下文内:返回一个已安装、
 * 已启用且可见 Skill 的有界完整指令与随包资源清单,记录版本/内容哈希溯源,
 * 且不会永久污染后续的用户轮次。
 */
@Service
public class SkillActivationService {

    private final SkillQueryService queryService;
    private final SkillRepository repository;
    private final SkillProperties properties;

    public SkillActivationService(SkillQueryService queryService,
                                  SkillRepository repository,
                                  SkillProperties properties) {
        this.queryService = queryService;
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * 为一次运行激活一个已安装且已启用的 Skill。
     *
     * @throws SkillImportException Skill 未知、已停用或没有已安装版本时抛出
     */
    @Transactional
    public ActivatedSkill activate(UUID projectId, UUID runId, String skillId) {
        Skill skill = queryService.findSkill(skillId)
                .orElseThrow(() -> new SkillNotVisibleException(skillId));
        if (!skill.enabled()) {
            throw new SkillNotVisibleException(skillId);
        }
        if (skill.currentVersionId() == null) {
            throw new SkillNotVisibleException(skillId);
        }
        SkillVersion version = queryService.findVersion(skill.currentVersionId())
                .orElseThrow(() -> new SkillImportException(
                        "Skill current version missing: " + skillId));
        String instructions = version.instructions();
        if (instructions != null
                && instructions.length() > properties.getActivationMaxInstructionBytes()) {
            instructions = instructions.substring(
                    0, properties.getActivationMaxInstructionBytes()) + "…";
        }

        repository.recordActivation(projectId, runId, skill.skillId(), version.id(),
                version.sourceIdentity(), version.contentHash());

        return new ActivatedSkill(skill.skillId(), skill.name(), version.id(),
                version.versionNo(), version.contentHash(), instructions,
                resourceInventory(version.id()));
    }

    private java.util.List<String> resourceInventory(UUID versionId) {
        return queryService.listFileSummaries(versionId).stream()
                .map(summary -> summary.relativePath())
                .filter(path -> !"SKILL.md".equals(path))
                .limit(properties.getMaxResourcesListed())
                .toList();
    }

    public record ActivatedSkill(String skillId, String name, UUID versionId,
                                 int versionNo, String contentHash, String instructions,
                                 java.util.List<String> resources) {
    }
}