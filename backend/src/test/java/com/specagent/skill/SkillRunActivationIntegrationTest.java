package com.specagent.skill;

import com.specagent.access.*;
import com.specagent.access.identity.IdentityRepository;
import com.specagent.agent.runtime.RunService;
import com.specagent.capability.*;
import com.specagent.skill.domain.SkillPackageFile;
import com.specagent.skill.importing.SkillSourceFile;
import com.specagent.skill.registry.SkillImportService;
import com.specagent.skill.runtime.*;
import com.specagent.workspace.project.ProjectService;
import java.nio.charset.StandardCharsets;
import java.util.*;
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
class SkillRunActivationIntegrationTest {
    @Autowired IdentityRepository identities;
    @Autowired ProjectService projects;
    @Autowired RunService runs;
    @Autowired SkillImportService imports;
    @Autowired SkillActivationService activations;
    @Autowired SkillResourceService resources;
    @Autowired CapabilityRuntime runtime;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.specagent.workspace.context.ContextBuilder contexts;
    @Autowired com.specagent.agent.snapshot.AgentInputSnapshotBuilder snapshotBuilder;
    @Autowired com.specagent.agent.snapshot.AgentInputProjectionRepository projections;

    private record Fixture(ActorContext actor,UUID project,UUID run,SkillImportService.InstalledResult skill) {}
    private <T> T as(ActorContext actor,Supplier<T> action) {
        try(var scope=ActorContextHolder.bind(actor)) {return action.get();}
    }
    private Fixture fixture() {
        UUID org=UUID.randomUUID(),user=UUID.randomUUID();
        identities.insertOrganizationIfAbsent(org,"skill-run","STANDARD");
        identities.insertUserIfAbsent(user,"skill-run-owner");
        identities.upsertMembership(org,user,OrgRole.OWNER);
        ActorContext actor=ActorContext.oidc(user,org);
        return as(actor,()-> {
            UUID project=projects.createProject("skill-run-project").id();
            UUID run=newRun(project);
            String md="---\nname: run-private\ndescription: test\n---\nRun-scoped resource body.";
            var staged=imports.stageBuiltin("run-private",md,List.of(new SkillSourceFile(
                    "SKILL.md",md.getBytes(StandardCharsets.UTF_8),SkillPackageFile.FileKind.SKILL_MD)),md.length());
            var skill=imports.install(staged.stagedImportId());imports.enable(skill.skillRowId());
            return new Fixture(actor,project,run,skill);
        });
    }
    private UUID newRun(UUID project) {
        var run=runs.createQueuedDraftQuestion(project);
        return runs.claimDecisionCycleRun(run.id()).orElseThrow().id();
    }
    private void activate(Fixture f) {as(f.actor(),()->activations.activate(f.project(),f.run(),f.skill().skillId()));}
    private CapabilityResult read(Fixture f,UUID run,String key) {
        return as(f.actor(),()->runtime.invoke(key,SkillReadResourceHostTool.CAPABILITY_ID,
                f.project(),run,Map.of("versionId",f.skill().versionId().toString(),"path","SKILL.md")));
    }

    private com.specagent.workspace.context.ContextSnapshot context(Fixture f,UUID run) {
        return as(f.actor(),()->contexts.buildFromActiveRoute(f.project(),run,
                com.specagent.workspace.context.ContextOperationType.INITIAL));
    }
    private com.specagent.agent.protocol.AgentInputSnapshot snapshot(Fixture f,UUID run,
            com.specagent.workspace.context.ContextSnapshot context) {
        var result=new java.util.concurrent.atomic.AtomicReference<com.specagent.agent.protocol.AgentInputSnapshot>();
        return as(f.actor(),()-> {
            com.specagent.agent.runtime.WorkspaceExecutionContext.runWith(run,()->result.set(snapshotBuilder.build(context)));
            return result.get();
        });
    }

    @Test void freshAndFrozenPromptReuseVerifiedSkillBodyWithinExecutingRun() {
        var f=fixture();activate(f);read(f,f.run(),"prompt-source-"+UUID.randomUUID());
        var context=context(f,f.run());var first=snapshot(f,f.run(),context);
        assertThat(first.capabilityResults()).extracting(com.specagent.agent.protocol.CapabilityResultView::capabilityId)
                .contains(SkillReadResourceHostTool.CAPABILITY_ID);
        assertThat(snapshot(f,f.run(),context)).isEqualTo(first);
    }

    @Test void unrelatedUserTurnDoesNotReinjectSkillBodyFromSameRoute() {
        var f=fixture();activate(f);read(f,f.run(),"prior-turn-"+UUID.randomUUID());
        UUID another=as(f.actor(),()->newRun(f.project()));
        assertThat(snapshot(f,another,context(f,another)).capabilityResults())
                .extracting(com.specagent.agent.protocol.CapabilityResultView::capabilityId)
                .doesNotContain(SkillReadResourceHostTool.CAPABILITY_ID,SkillActivateHostTool.CAPABILITY_ID);
    }

    @Test void disabledSourceRefusesFrozenPromptWithoutRewritingItsAuditPayload() {
        var f=fixture();activate(f);read(f,f.run(),"frozen-disable-"+UUID.randomUUID());
        var context=context(f,f.run());snapshot(f,f.run(),context);
        String original=projections.findBySnapshotId(context.id()).orElseThrow().payload();
        as(f.actor(),()->{imports.disable(f.skill().skillRowId());return null;});
        assertThatThrownBy(()->snapshot(f,f.run(),context))
                .isInstanceOf(com.specagent.agent.snapshot.FrozenSourceAuthorizationRefusedException.class)
                .hasMessage("FROZEN_INPUT_SOURCE_NOT_AUTHORIZED");
        assertThat(projections.findBySnapshotId(context.id()).orElseThrow().payload()).isEqualTo(original);
    }

    @Test void frozenSkillBodyCannotBeReusedOutsideAnExecutingRunContext() {
        var f=fixture();activate(f);read(f,f.run(),"unbound-freeze-"+UUID.randomUUID());
        var context=context(f,f.run());snapshot(f,f.run(),context);
        assertThatThrownBy(()->as(f.actor(),()->snapshotBuilder.build(context)))
                .isInstanceOf(com.specagent.agent.snapshot.FrozenSourceAuthorizationRefusedException.class);
    }

    @Test void frozenSkillBodyCannotBeBorrowedByAnUnrelatedExecutingRun() {
        var f=fixture();activate(f);read(f,f.run(),"borrow-frozen-"+UUID.randomUUID());
        var context=context(f,f.run());snapshot(f,f.run(),context);
        UUID another=as(f.actor(),()->newRun(f.project()));
        assertThatThrownBy(()->snapshot(f,another,context))
                .isInstanceOf(com.specagent.agent.snapshot.FrozenSourceAuthorizationRefusedException.class);
    }

    @Test void organizationVisibilityAloneDoesNotAuthorizeExecutionResourceRead() {
        var f=fixture();
        assertThat(as(f.actor(),()->resources.readResource(f.skill().versionId(),"SKILL.md").content()))
                .contains("Run-scoped resource body");
        String key="unactivated-"+UUID.randomUUID();
        assertThatThrownBy(()->read(f,f.run(),key)).isInstanceOf(SkillResourceRejectedException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM capability_invocations WHERE invocation_key=?",Integer.class,key)).isZero();
    }

    @Test void actualActivationAndResourceCapabilitySucceedAndReplayWithoutDuplicateAuthority() {
        var f=fixture();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM runtime_tasks WHERE workspace_run_id=?",Integer.class,f.run())).isZero();
        String activationKey="activate-"+UUID.randomUUID();
        var first=as(f.actor(),()->runtime.invoke(activationKey,SkillActivateHostTool.CAPABILITY_ID,
                f.project(),f.run(),Map.of("skillId",f.skill().skillId())));
        assertThat(first.status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
        activate(f);
        String key="read-"+UUID.randomUUID();
        assertThat(read(f,f.run(),key).content().get("content").toString()).contains("Run-scoped resource body");
        assertThat(read(f,f.run(),key).status()).isEqualTo(CapabilityResult.Status.REPLAYED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM skill_activations WHERE verified_run_id=?",Integer.class,f.run())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor_user_id FROM skill_activations WHERE verified_run_id=?",UUID.class,f.run()))
                .isEqualTo(f.actor().userId());
    }

    @Test void unrelatedRunInSameProjectDoesNotInheritActivationOrAnAllegedRootId() {
        var f=fixture();activate(f);
        UUID another=as(f.actor(),()->newRun(f.project()));
        jdbc.update("UPDATE agent_runs SET root_run_id=? WHERE id=?",f.run(),another);
        assertThatThrownBy(()->read(f,another,"unrelated-"+UUID.randomUUID()))
                .isInstanceOf(SkillResourceRejectedException.class);
    }

    @Test void realContinuationLinkReusesVerifiedImmutableAncestorActivation() {
        var f=fixture();activate(f);
        jdbc.update("UPDATE agent_runs SET status='completed',completed_at=now() WHERE id=?",f.run());
        UUID child=as(f.actor(),()->runs.createContinueRun(runs.getRun(f.run()).orElseThrow()).run().id());
        // The public continuation creator persists the real linkage/delegation;
        // this fixture marks it executing, without pretending to run a model Worker.
        jdbc.update("UPDATE agent_runs SET status='running' WHERE id=?",child);
        assertThat(read(f,child,"continuation-"+UUID.randomUUID()).status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
    }

    @Test void crossProjectParentCannotDonateAnActivationEvenWithSameActorAndOrg() {
        var f=fixture();activate(f);
        UUID otherProject=as(f.actor(),()->projects.createProject("other-project").id());
        UUID otherRun=as(f.actor(),()->newRun(otherProject));
        jdbc.update("UPDATE agent_runs SET trigger_type='continue_cycle',parent_run_id=? WHERE id=?",f.run(),otherRun);
        assertThatThrownBy(()->as(f.actor(),()->resources.readActivatedResource(otherProject,otherRun,
                f.skill().versionId(),"SKILL.md"))).isInstanceOf(SkillResourceRejectedException.class);
    }

    @Test void sameProjectManagerCannotBorrowAnotherUsersExecutingRun() {
        var f=fixture();activate(f);
        UUID user=UUID.randomUUID();identities.insertUserIfAbsent(user,"other-manager");
        identities.upsertMembership(f.actor().orgId(),user,OrgRole.OWNER);
        identities.upsertProjectMember(f.project(),user,f.actor().orgId(),ProjectRole.MANAGER);
        var other=ActorContext.oidc(user,f.actor().orgId());
        assertThatThrownBy(()->as(other,()->resources.readActivatedResource(f.project(),f.run(),
                f.skill().versionId(),"SKILL.md"))).isInstanceOf(AccessDeniedException.class);
    }

    @Test void disablingSkillRefusesAlreadyRecordedResourceReplay() {
        var f=fixture();activate(f);String key="disable-replay-"+UUID.randomUUID();
        assertThat(read(f,f.run(),key).status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
        as(f.actor(),()->{imports.disable(f.skill().skillRowId());return null;});
        assertThatThrownBy(()->read(f,f.run(),key)).isInstanceOf(SkillResourceRejectedException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM capability_invocations WHERE invocation_key=?",String.class,key)).isEqualTo("SUCCEEDED");
    }

    @Test void disablingSkillAlsoRefusesRecordedActivationInstructions() {
        var f=fixture();String key="activation-replay-"+UUID.randomUUID();
        as(f.actor(),()->runtime.invoke(key,SkillActivateHostTool.CAPABILITY_ID,f.project(),f.run(),Map.of("skillId",f.skill().skillId())));
        as(f.actor(),()->{imports.disable(f.skill().skillRowId());return null;});
        assertThatThrownBy(()->as(f.actor(),()->runtime.invoke(key,SkillActivateHostTool.CAPABILITY_ID,
                f.project(),f.run(),Map.of("skillId",f.skill().skillId())))).isInstanceOf(SkillNotVisibleException.class);
    }

    @Test void revokedMembershipRefusesAlreadyRecordedResourceReplay() {
        var f=fixture();activate(f);String key="revoke-replay-"+UUID.randomUUID();read(f,f.run(),key);
        identities.updateMembershipStatus(f.actor().orgId(),f.actor().userId(),"DISABLED");
        assertThatThrownBy(()->read(f,f.run(),key)).isInstanceOf(AccessDeniedException.class);
    }

    @Test void terminalRunCannotReturnRecordedResources() {
        var f=fixture();activate(f);String key="terminal-replay-"+UUID.randomUUID();read(f,f.run(),key);
        jdbc.update("UPDATE agent_runs SET status='failed' WHERE id=?",f.run());
        assertThatThrownBy(()->read(f,f.run(),key)).isInstanceOf(com.specagent.runtime.execution.TaskOwnershipLostException.class);
    }

    @Test void missingRunCannotCreateActivationAuthority() {
        var f=fixture();
        assertThatThrownBy(()->as(f.actor(),()->activations.activate(f.project(),null,f.skill().skillId())))
                .isInstanceOf(SkillNotVisibleException.class);
    }

    @Test void historicalUnverifiedActivationDoesNotAuthorizeResources() {
        var f=fixture();
        jdbc.update("INSERT INTO skill_activations (id,org_id,project_id,run_id,skill_id,version_id,source_identity,content_hash,created_at)"
                +" VALUES(?,?,?,?,?,?,?,?,now())",UUID.randomUUID(),f.actor().orgId(),f.project(),f.run(),
                f.skill().skillId(),f.skill().versionId(),"legacy","legacy");
        assertThatThrownBy(()->read(f,f.run(),"legacy-"+UUID.randomUUID())).isInstanceOf(SkillResourceRejectedException.class);
    }

    @Test void changingCapabilityCannotBypassPrivateReplayGuard() {
        var f=fixture();activate(f);String key="spoof-capability-"+UUID.randomUUID();read(f,f.run(),key);
        var spoof=as(f.actor(),()->runtime.invoke(key,"no.such.capability",f.project(),f.run(),
                Map.of("versionId",f.skill().versionId().toString(),"path","SKILL.md")));
        assertThat(spoof.status()).isEqualTo(CapabilityResult.Status.FAILED);
        assertThat(spoof.content()).containsEntry("reason","CAPABILITY_INVOCATION_IDENTITY_MISMATCH");
        assertThat(spoof.content()).doesNotContainKey("content");
    }

    @Test void verifiedAuthorityCannotBeStampedWithAnotherUsersDelegation() {
        var f=fixture();UUID user=UUID.randomUUID();identities.insertUserIfAbsent(user,"forged-owner");
        assertThatThrownBy(()->jdbc.update("INSERT INTO skill_activations "
                +"(id,org_id,project_id,run_id,verified_run_id,actor_user_id,actor_source,skill_id,version_id,source_identity,content_hash,created_at) "
                +"VALUES(?,?,?,?,?,?,?,?,?,?,?,now())",UUID.randomUUID(),f.actor().orgId(),f.project(),f.run(),f.run(),
                user,"OIDC",f.skill().skillId(),f.skill().versionId(),"forged","forged"))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
                .hasMessageContaining("skill_activation_verified_delegation");
    }
}
