package com.specagent.agent.runtime;

import com.specagent.access.*;
import com.specagent.access.identity.IdentityRepository;
import com.specagent.modelsettings.*;
import com.specagent.workspace.project.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class WorkspaceExecutionAuthorizationIntegrationTest {
    @Autowired IdentityRepository identities;
    @Autowired IdentityModeProperties mode;
    @Autowired ProjectService projects;
    @Autowired ProjectDeletionService deletion;
    @Autowired RunService runs;
    @Autowired AgentRunService checkpoints;
    @Autowired WorkspaceRunActorRepository actors;
    @Autowired WorkspaceExecutionAuthorization authorization;
    @Autowired RunWorker worker;
    @Autowired OpenCodeSettingsRepository settings;
    @Autowired JdbcTemplate jdbc;
    @Autowired AuthorizationRevocationOutbox outbox;
    @Autowired com.specagent.runtime.execution.RuntimeAuthorizationRevoker revoker;
    private UUID org,user,project,run;
    private AgentRun claimed;
    @BeforeEach void fixture() {
        org=UUID.randomUUID();user=UUID.randomUUID();
        identities.insertUserIfAbsent(user,"Workspace delegated owner");
        identities.insertOrganizationIfAbsent(org,"Workspace execution","STANDARD");
        identities.upsertMembership(org,user,OrgRole.OWNER);
        try(var actor=ActorContextHolder.bind(ActorContext.oidc(user,org))) {
            var p=projects.createProject("Workspace delegation");project=p.id();
            settings.upsert(new OpenCodeSettings("synthetic-workspace-key","test","fake-model",Instant.now(),Instant.now()));
            var queued=runs.createQueuedDraftQuestion(project);run=queued.id();
            claimed=runs.claimDecisionCycleRun(run).orElseThrow();
        }
        mode.setMode("OIDC");
    }
    @AfterEach void clean() {
        mode.setMode("LOCAL");ActorContextHolder.clear();
        identities.updateUserStatus(user,"ACTIVE");
        identities.upsertMembership(org,user,OrgRole.OWNER);
        identities.upsertProjectMember(project,user,org,ProjectRole.MANAGER);
        jdbc.update("UPDATE agent_runs SET status='failed' WHERE project_id=? AND status NOT IN ('completed','failed')",project);
        try(var actor=ActorContextHolder.bind(ActorContext.oidc(user,org))) {deletion.deleteProject(project);}
        jdbc.update("DELETE FROM opencode_settings WHERE org_id=?",org);
        jdbc.update("DELETE FROM model_provider_settings WHERE org_id=?",org);
        jdbc.update("DELETE FROM profiles WHERE org_id=?",org);
        jdbc.update("DELETE FROM organization_members WHERE org_id=?",org);
        jdbc.update("DELETE FROM organizations WHERE id=?",org);
        jdbc.update("DELETE FROM app_users WHERE id=?",user);
    }
    @Test void singleExecutorRestoresPersistedOidcActorAndCompletesRealFakeDecisionLoop() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM runtime_tasks WHERE workspace_run_id=?",Integer.class,run)).isZero();
        assertThat(actors.require(run)).isEqualTo(ActorContext.oidc(user,org));
        assertThat(ActorContextHolder.current()).isNull();
        worker.executeRun(claimed);
        var finished=runs.getRun(run).orElseThrow();
        assertThat(finished.status()).isEqualTo(AgentRunStatus.COMPLETED);
        assertThat(finished.producedNodeId()).isNotNull();
        assertThat(ActorContextHolder.current()).isNull();
        assertThat(WorkspaceExecutionContext.currentRun()).isNull();
    }
    @Test void missingDelegationFailsClosedAndLeavesOnlyAControlFailure() {
        jdbc.update("DELETE FROM workspace_run_authorizations WHERE run_id=?",run);
        assertThatThrownBy(()->worker.executeRun(claimed)).isInstanceOf(AccessDeniedException.class);
        assertThat(runs.getRun(run).orElseThrow().status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nodes WHERE project_id=?",Integer.class,project)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run_events WHERE run_id=? AND event_type='RUN_FAILED'",Integer.class,run)).isEqualTo(1);
    }
    @Test void revocationBlocksLateCheckpointEvenWithoutThreadActorOrTaskRow() {
        identities.updateMembershipStatus(org,user,"DISABLED");
        assertThatThrownBy(()->checkpoints.markModelCalled(run,"late model output")).isInstanceOf(AccessDeniedException.class);
        assertThat(runs.getRun(run).orElseThrow().status()).isEqualTo(AgentRunStatus.RUNNING);
        assertThat(runs.getRun(run).orElseThrow().trace()).doesNotContain("late model output");
    }
    @Test void standaloneRevocationConvergesThroughOutboxWithoutDuplicateTerminalEvents() {
        identities.updateMembershipStatus(org,user,"DISABLED");
        outbox.append(user,org);revoker.poll();
        assertThat(runs.getRun(run).orElseThrow().status()).isEqualTo(AgentRunStatus.FAILED);
        outbox.append(user,org);revoker.poll();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run_events WHERE run_id=? AND event_type='RUN_FAILED'",Integer.class,run)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nodes WHERE project_id=?",Integer.class,project)).isZero();
    }
    @Test void losingProjectWriteRoleBlocksExecutionDespiteRemainingOrganizationOwner() {
        identities.upsertProjectMember(project,user,org,ProjectRole.VIEWER);
        assertThatThrownBy(()->authorization.authorize(run)).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM nodes WHERE project_id=?",Integer.class,project)).isZero();
    }
    @Test void localSourceCannotBeReplayedInOidcMode() {
        jdbc.update("UPDATE workspace_run_authorizations SET actor_source='LOCAL' WHERE run_id=?",run);
        assertThatThrownBy(()->authorization.authorize(run)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void disabledInitiatorDoesNotBecomeProjectCreatorOrSystemActor() {
        identities.updateUserStatus(user,"DISABLED");
        assertThatThrownBy(()->authorization.authorize(run)).isInstanceOf(AccessDeniedException.class);
    }
    @Test void checkpointCannotBorrowAnotherBoundRunContext() {
        assertThatThrownBy(()->WorkspaceExecutionContext.runWith(UUID.randomUUID(),
                ()->checkpoints.markModelCalled(run,"wrong context output")))
                .isInstanceOf(com.specagent.runtime.execution.TaskOwnershipLostException.class);
        assertThat(runs.getRun(run).orElseThrow().trace()).doesNotContain("wrong context output");
        assertThat(WorkspaceExecutionContext.currentRun()).isNull();
    }
}
