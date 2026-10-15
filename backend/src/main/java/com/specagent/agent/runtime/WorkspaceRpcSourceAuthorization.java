package com.specagent.agent.runtime;

import com.specagent.access.*;
import com.specagent.agent.protocol.*;
import com.specagent.agent.snapshot.SkillObservationAuthorization;
import com.specagent.common.Hashes;
import com.specagent.skill.runtime.SkillResourceService;
import java.util.*;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** Immutable source bindings are derived from host-owned frozen input, never from the Python prompt. */
@Component
public class WorkspaceRpcSourceAuthorization {
    public record Binding(UUID snapshotId,String payloadHash,List<UUID> skills,
            List<com.specagent.capability.RecordedMcpSourcePort.Reference> mcp) {}
    private final NamedParameterJdbcTemplate jdbc;
    private final SkillResourceService resources;
    private final SkillObservationAuthorization policy;
    private final com.specagent.capability.RecordedMcpSourcePort mcp;
    public WorkspaceRpcSourceAuthorization(NamedParameterJdbcTemplate jdbc,SkillResourceService resources,
            SkillObservationAuthorization policy,com.specagent.capability.RecordedMcpSourcePort mcp) {
        this.jdbc=jdbc;this.resources=resources;this.policy=policy;this.mcp=mcp;
    }

    public Binding capture(UUID run,ActorContext actor,AgentInputSnapshot supplied) {
        var rows=jdbc.queryForList("SELECT project_id,context_snapshot_id FROM agent_runs WHERE id=:run",Map.of("run",run));
        if(rows.size()!=1) throw refused();
        UUID project=(UUID)rows.getFirst().get("project_id");
        UUID snapshot=(UUID)rows.getFirst().get("context_snapshot_id");
        if(supplied!=null) {
            if(!project.equals(supplied.projectId())) throw refused();
            try {snapshot=UUID.fromString(supplied.snapshotId());} catch(RuntimeException ex) {throw refused();}
        }
        if(snapshot==null) return new Binding(null,null,List.of(),List.of());
        var frozen=load(snapshot,project);
        var projection=decode(frozen,project,snapshot);
        var skills=references(projection);
        if(supplied!=null && !skills.equals(references(supplied))) throw refused();
        var connections=mcpReferences(projection);
        if(supplied!=null && !connections.equals(mcpReferences(supplied))) throw refused();
        verifySkills(run,project,actor,skills);
        verifyMcp(actor,connections);
        return new Binding(snapshot,(String)frozen.get("payload_hash"),skills,connections);
    }

    public void verify(UUID run,ActorContext actor,Binding binding) {
        UUID project=jdbc.queryForObject("SELECT project_id FROM agent_runs WHERE id=:run",Map.of("run",run),UUID.class);
        if(binding.snapshotId()!=null) {
            var frozen=load(binding.snapshotId(),project);
            if(!binding.payloadHash().equals(frozen.get("payload_hash"))) throw refused();
            var input=decode(frozen,project,binding.snapshotId());
            if(!binding.skills().equals(references(input)) || !binding.mcp().equals(mcpReferences(input))) throw refused();
        } else {
            if(binding.payloadHash()!=null || !binding.skills().isEmpty() || !binding.mcp().isEmpty()) throw refused();
            UUID attached=jdbc.queryForObject("SELECT context_snapshot_id FROM agent_runs WHERE id=:run",Map.of("run",run),UUID.class);
            if(attached!=null) {
                var input=decode(load(attached,project),project,attached);
                if(!references(input).isEmpty() || !mcpReferences(input).isEmpty()) throw refused();
            }
        }
        verifySkills(run,project,actor,binding.skills());
        verifyMcp(actor,binding.mcp());
    }

    private Map<String,Object> load(UUID snapshot,UUID project) {
        var rows=jdbc.queryForList("""
                SELECT p.payload,p.payload_hash FROM agent_input_projections p
                JOIN context_snapshots c ON c.id=p.snapshot_id
                WHERE p.snapshot_id=:snapshot AND c.project_id=:project
                """,Map.of("snapshot",snapshot,"project",project));
        if(rows.size()!=1) throw refused();return rows.getFirst();
    }
    private AgentInputSnapshot decode(Map<String,Object> frozen,UUID project,UUID snapshot) {
        try {
            String payload=(String)frozen.get("payload");
            if(!Hashes.sha256Hex(payload).equals(frozen.get("payload_hash"))) throw refused();
            var projection=AgentContracts.read(payload,AgentInputSnapshot.class);
            if(!project.equals(projection.projectId()) || !snapshot.toString().equals(projection.snapshotId())) throw refused();
            return projection;
        } catch(RuntimeException ex) {throw refused();}
    }
    private List<UUID> references(AgentInputSnapshot projection) {
        Set<UUID> result=new TreeSet<>();
        for(var observation:projection.capabilityResults()) {
            if(!policy.carriesPrivateBody(observation)) continue;
            Object raw=observation.provenance().get("versionId");
            try {result.add(UUID.fromString((String)raw));} catch(RuntimeException ex) {throw refused();}
        }
        if(result.size()>64) throw refused();
        return List.copyOf(result);
    }
    private void verifySkills(UUID run,UUID project,ActorContext actor,List<UUID> skills) {
        try(var context=ActorContextHolder.bind(actor)) {
            for(UUID version:skills) resources.requireActivatedVersion(project,run,version);
        } catch(RuntimeException ex) {throw refused();}
    }
    private List<com.specagent.capability.RecordedMcpSourcePort.Reference> mcpReferences(AgentInputSnapshot projection) {
        var refs=new ArrayList<com.specagent.capability.RecordedMcpSourcePort.Reference>();
        for(var result:projection.capabilityResults()) {
            if(!mcp.handles(result.capabilityId())) continue;
            try {refs.add(mcp.reference(result.capabilityId(),result.provenance()));} catch(RuntimeException ex) {throw refused();}
        }
        var sorted=refs.stream().distinct().sorted(Comparator.comparing(ref -> ref.rowId().toString()+":"+ref.version()+":"+ref.toolName())).toList();
        if(sorted.size()>64) throw refused();return sorted;
    }
    private void verifyMcp(ActorContext actor,List<com.specagent.capability.RecordedMcpSourcePort.Reference> refs) {
        try(var context=ActorContextHolder.bind(actor)) {for(var ref:refs)mcp.require(ref);}
        catch(RuntimeException ex) {throw refused();}
    }
    private static AccessDeniedException refused() {
        return AccessDeniedException.forbidden(AccessReason.UNAUTHENTICATED,"Workspace RPC sources are absent, changed or refused");
    }
}
