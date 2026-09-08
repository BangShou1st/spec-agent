package com.specagent.skill.discovery;

import com.specagent.skill.domain.Skill;
import com.specagent.skill.registry.SkillQueryService;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Decides whether Skill-related Host Function Tools ({@code skill.activate},
 * {@code skill.read_resource}) should be model-visible in a given snapshot.
 *
 * <p>These tools return procedural knowledge, so they are useful only when
 * the project actually has an installed + enabled Skill to activate.
 * Blanket visibility in every context would violate the
 * "installed != loaded" invariant and pollute every decision input with
 * tooling the model cannot use. The gating fact here is fully deterministic:
 * "does this project have any enabled Skill?" — never user wording.
 */
@Service
public class SkillHostToolVisibility {

    private final SkillQueryService queryService;

    public SkillHostToolVisibility(SkillQueryService queryService) {
        this.queryService = queryService;
    }

    public boolean anyEnabledSkill(UUID projectId) {
        if (projectId == null) {
            return false;
        }
        return queryService.listSkills().stream()
                .anyMatch(Skill::enabled);
    }
}