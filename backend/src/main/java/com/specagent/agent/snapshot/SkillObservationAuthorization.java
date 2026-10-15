package com.specagent.agent.snapshot;

import com.specagent.agent.protocol.CapabilityResultView;
import com.specagent.skill.runtime.SkillResourceRejectedException;
import com.specagent.skill.runtime.SkillResourceService;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Skill body lifetime applies to prompt observations as well as direct tool calls. */
@Component
public class SkillObservationAuthorization {
    private final SkillResourceService resources;
    private final RunAttributionLookupPort execution;
    public SkillObservationAuthorization(SkillResourceService resources,RunAttributionLookupPort execution) {
        this.resources=resources;this.execution=execution;
    }
    public boolean carriesPrivateBody(CapabilityResultView result) {
        if (!Set.of("skill.activate","skill.read_resource").contains(result.capabilityId())) return false;
        return !("FAILED".equals(result.status()) && result.content().keySet().equals(Set.of("reason"))
                && result.provenance().isEmpty());
    }
    public boolean visible(UUID project,CapabilityResultView result) {
        if (!carriesPrivateBody(result)) return true;
        UUID run=execution.currentExecutingRun();
        Object raw=result.provenance().get("versionId");
        if (run==null || !(raw instanceof String text)) return false;
        UUID version;
        try {version=UUID.fromString(text);} catch(IllegalArgumentException ex) {return false;}
        try {resources.requireActivatedVersion(project,run,version);return true;}
        catch(SkillResourceRejectedException ex) {return false;}
        // Role/ownership/database failures propagate; never keep building other
        // private prompt content after losing the execution's authority.
    }
}
