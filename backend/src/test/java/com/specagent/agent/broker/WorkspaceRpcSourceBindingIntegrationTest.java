package com.specagent.agent.broker;

import com.specagent.access.*;
import com.specagent.access.identity.IdentityRepository;
import com.specagent.agent.protocol.AgentInputSnapshot;
import com.specagent.agent.runtime.*;
import com.specagent.agent.snapshot.AgentInputSnapshotBuilder;
import com.specagent.common.Hashes;
import com.specagent.skill.domain.SkillPackageFile;
import com.specagent.skill.importing.SkillSourceFile;
import com.specagent.skill.registry.SkillImportService;
import com.specagent.skill.runtime.SkillActivationService;
import com.specagent.capability.CapabilityRuntime;
import com.specagent.workspace.context.*;
import com.specagent.workspace.project.ProjectService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class WorkspaceRpcSourceBindingIntegrationTest {
    @Autowired IdentityRepository identities;
    @Autowired ProjectService projects;
    @Autowired RunService runs;
    @Autowired SkillImportService imports;
    @Autowired SkillActivationService activations;
    @Autowired CapabilityRuntime runtime;
    @Autowired ContextBuilder contexts;
    @Autowired AgentInputSnapshotBuilder snapshots;
    @Autowired WorkspaceRpcGrants grants;
    @Autowired JdbcTemplate jdbc;
    private record Fixture(ActorContext actor,UUID project,UUID run,SkillImportService.InstalledResult skill,AgentInputSnapshot snapshot) {}
    private <T> T as(ActorContext actor,Supplier<T> call) {try(var scope=ActorContextHolder.bind(actor)){return call.get();}}
    private <T> T bound(Fixture f,Supplier<T> call) {
        AtomicReference<T> result=new AtomicReference<>();
        return as(f.actor(),()->{WorkspaceExecutionContext.runWith(f.run(),()->result.set(call.get()));return result.get();});
    }
    private Fixture fixture() {
        UUID org=UUID.randomUUID(),user=UUID.randomUUID();
        identities.insertOrganizationIfAbsent(org,"rpc-sources","STANDARD");
        identities.insertUserIfAbsent(user,"rpc-source-owner");identities.upsertMembership(org,user,OrgRole.OWNER);
        ActorContext actor=ActorContext.oidc(user,org);
        return as(actor,()-> {
            UUID project=projects.createProject("rpc-source-project").id();
            UUID run=runs.createQueuedDraftQuestion(project).id();runs.claimDecisionCycleRun(run).orElseThrow();
            String md="---\nname: rpc-source\ndescription: test\n---\nPrivate RPC source body.";
            var staged=imports.stageBuiltin("rpc-source",md,List.of(new SkillSourceFile("SKILL.md",
                    md.getBytes(StandardCharsets.UTF_8),SkillPackageFile.FileKind.SKILL_MD)),md.length());
            var skill=imports.install(staged.stagedImportId());imports.enable(skill.skillRowId());
            activations.activate(project,run,skill.skillId());
            runtime.invoke("rpc-resource-"+UUID.randomUUID(),"skill.read_resource",project,run,
                    Map.of("versionId",skill.versionId().toString(),"path","SKILL.md"));
            var context=contexts.buildFromActiveRoute(project,run,ContextOperationType.INITIAL);
            AtomicReference<AgentInputSnapshot> snapshot=new AtomicReference<>();
            WorkspaceExecutionContext.runWith(run,()->snapshot.set(snapshots.build(context)));
            return new Fixture(actor,project,run,skill,snapshot.get());
        });
    }
    private WorkspaceRpcGrants.Ticket ticket(Fixture f) {
        return bound(f,()->grants.issue(f.run(),"DECISION",2,Duration.ofSeconds(120),f.snapshot()));
    }
    private String tokenHash(WorkspaceRpcGrants.Ticket ticket) {return Hashes.sha256Hex(ticket.value());}
    private int consumed(WorkspaceRpcGrants.Ticket ticket) {
        return jdbc.queryForObject("SELECT consumed_calls FROM workspace_rpc_grants WHERE token_hash=?",Integer.class,tokenHash(ticket));
    }
    private WorkspaceRpcGrants.Invocation admit(Fixture f,WorkspaceRpcGrants.Ticket ticket) {
        return grants.admit(ticket.value(),f.run(),"DECISION","a".repeat(64));
    }

    @Test void frozenSnapshotAndSkillReferencesArePinnedAndValidCallFinishes() {
        var f=fixture();var ticket=ticket(f);
        assertThat(jdbc.queryForObject("SELECT source_snapshot_id FROM workspace_rpc_grants WHERE token_hash=?",UUID.class,tokenHash(ticket)))
                .isEqualTo(UUID.fromString(f.snapshot().snapshotId()));
        assertThat(jdbc.queryForObject("SELECT cardinality(source_skill_version_ids) FROM workspace_rpc_grants WHERE token_hash=?",Integer.class,tokenHash(ticket))).isEqualTo(1);
        var invocation=admit(f,ticket);AtomicBoolean written=new AtomicBoolean();invocation.finish(true,()->written.set(true));
        assertThat(written.get()).isTrue();assertThat(consumed(ticket)).isEqualTo(1);
    }
    @Test void disabledSkillRefusesAlreadyIssuedTicketWithoutConsumingBudget() {
        var f=fixture();var ticket=ticket(f);
        as(f.actor(),()->{imports.disable(f.skill().skillRowId());return null;});
        assertThatThrownBy(()->admit(f,ticket)).isInstanceOf(AccessDeniedException.class);
        assertThat(consumed(ticket)).isZero();
    }
    @Test void deletedVersionDoesNotDisappearFromTicketsAuthorityRequirements() {
        var f=fixture();var ticket=ticket(f);
        as(f.actor(),()->{imports.delete(f.skill().skillRowId());return null;});
        assertThatThrownBy(()->admit(f,ticket)).isInstanceOf(AccessDeniedException.class);
        assertThat(consumed(ticket)).isZero();
    }
    @Test void sourceRevokedDuringModelCallRefusesLateCompletionAndRecordsUnknown() {
        var f=fixture();var ticket=ticket(f);var invocation=admit(f,ticket);
        as(f.actor(),()->{imports.disable(f.skill().skillRowId());return null;});
        AtomicBoolean written=new AtomicBoolean();
        assertThatThrownBy(()->invocation.finish(true,()->written.set(true))).isInstanceOf(AccessDeniedException.class);
        assertThat(written.get()).isFalse();invocation.unknown();
        assertThat(jdbc.queryForObject("SELECT c.status FROM workspace_rpc_calls c JOIN workspace_rpc_grants g ON g.id=c.grant_id WHERE g.token_hash=?",String.class,tokenHash(ticket))).isEqualTo("UNKNOWN");
        assertThat(consumed(ticket)).isEqualTo(1);
    }
    @Test void changedFrozenBytesCannotUseAnOldTicketEvenWithARecomputedHash() {
        var f=fixture();var ticket=ticket(f);UUID snapshot=UUID.fromString(f.snapshot().snapshotId());
        String original=jdbc.queryForObject("SELECT payload FROM agent_input_projections WHERE snapshot_id=?",String.class,snapshot);
        String changed=original+" ";
        jdbc.update("UPDATE agent_input_projections SET payload=?,payload_hash=? WHERE snapshot_id=?",changed,Hashes.sha256Hex(changed),snapshot);
        assertThatThrownBy(()->admit(f,ticket)).isInstanceOf(AccessDeniedException.class);assertThat(consumed(ticket)).isZero();
    }
    @Test void deletedFrozenProjectionDoesNotTurnTicketIntoAnUnboundGrant() {
        var f=fixture();var ticket=ticket(f);
        jdbc.update("DELETE FROM agent_input_projections WHERE snapshot_id=?",UUID.fromString(f.snapshot().snapshotId()));
        assertThatThrownBy(()->admit(f,ticket)).isInstanceOf(AccessDeniedException.class);assertThat(consumed(ticket)).isZero();
    }
    @Test void legacyIssueDerivesSourcesFromRunsAttachedFrozenInput() {
        var f=fixture();jdbc.update("UPDATE agent_runs SET context_snapshot_id=? WHERE id=?",UUID.fromString(f.snapshot().snapshotId()),f.run());
        var ticket=bound(f,()->grants.issue(f.run(),"DECISION",1,Duration.ofSeconds(120)));
        as(f.actor(),()->{imports.disable(f.skill().skillRowId());return null;});
        assertThatThrownBy(()->admit(f,ticket)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void initiallyUnboundTicketCannotExpandToLaterAttachedPrivateSources() {
        var f=fixture();var ticket=bound(f,()->grants.issue(f.run(),"DECISION",1,Duration.ofSeconds(120)));
        jdbc.update("UPDATE agent_runs SET context_snapshot_id=? WHERE id=?",UUID.fromString(f.snapshot().snapshotId()),f.run());
        assertThatThrownBy(()->admit(f,ticket)).isInstanceOf(AccessDeniedException.class);assertThat(consumed(ticket)).isZero();
    }
    @Test void issuingTicketForAnAlreadyDisabledSourceFailsBeforeCreatingGrant() {
        var f=fixture();as(f.actor(),()->{imports.disable(f.skill().skillRowId());return null;});
        assertThatThrownBy(()->ticket(f)).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_rpc_grants WHERE run_id=?",Integer.class,f.run())).isZero();
    }
}
