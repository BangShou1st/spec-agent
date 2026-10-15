package com.specagent.assistant;

import com.specagent.access.*;
import com.specagent.access.identity.IdentityRepository;
import com.specagent.assistant.conversation.*;
import com.specagent.assistant.runtime.*;
import com.specagent.runtime.execution.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
class GaExecutionAuthorizationIntegrationTest {
    @Autowired GaExecutionAuthorization authorization;
    @Autowired GaRunActorRepository actors;
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired GlobalAssistantRunRepository runs;
    @Autowired IdentityRepository identities;
    @Autowired IdentityModeProperties mode;
    @Autowired RuntimeTaskRepository tasks;
    @Autowired TaskWriteGuard writes;
    @Autowired AuthorizationGuard guard;
    @Autowired GlobalAssistantStreamService streams;
    @Autowired GlobalAssistantApplicationService application;
    @Autowired AuthorizationRevocationOutbox outbox;
    @Autowired RuntimeAuthorizationRevoker revoker;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private UUID org, user, thread, run;
    private String sessionId;

    @BeforeEach void createDelegation() {
        org=UUID.randomUUID(); user=UUID.randomUUID();
        identities.insertOrganizationIfAbsent(org,"GA authorization test","STANDARD");
        identities.insertUserIfAbsent(user,"GA delegated user");
        identities.upsertMembership(org,user,OrgRole.MEMBER);
        try(var ignored=ActorContextHolder.bind(ActorContext.oidc(user,org))) {
            thread=conversations.createThread().id();
            run=runs.create(thread,"ga.v1","ga.v1","v1").id();
        }
    }
    @AfterEach void clean() {
        mode.setMode("LOCAL"); ActorContextHolder.clear();
        if(sessionId!=null) jdbc.update("DELETE FROM spring_session WHERE session_id=?",sessionId);
        jdbc.update("DELETE FROM global_assistant_run_events WHERE run_id=?",run);
        jdbc.update("DELETE FROM global_assistant_runs WHERE id=?",run);
        jdbc.update("DELETE FROM global_assistant_threads WHERE id=?",thread);
        jdbc.update("DELETE FROM organization_members WHERE org_id=?",org);
        jdbc.update("DELETE FROM organizations WHERE id=?",org);
        jdbc.update("DELETE FROM app_users WHERE id=?",user);
    }
    private ActorContext authorize() {
        return new TransactionTemplate(transactions).execute(tx -> authorization.lockAndAuthorize(run,1));
    }
    @Test void singleExecutorOidcRestoresPersistedActorWithoutRequestOrTask() {
        mode.setMode("OIDC");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM runtime_tasks WHERE ga_run_id=?",Integer.class,run)).isZero();
        assertThat(authorize()).isEqualTo(ActorContext.oidc(user,org));
        assertThat(ActorContextHolder.current()).isNull();
    }
    @Test void missingDelegationNeverFallsBackToLocalOrThreadOwner() {
        jdbc.update("DELETE FROM ga_run_authorizations WHERE run_id=?",run);
        assertThatThrownBy(this::authorize).isInstanceOf(AccessDeniedException.class);
    }
    @Test void disabledUserCannotUsePreviouslyAuthorizedRun() {
        identities.updateUserStatus(user,"DISABLED");
        assertThatThrownBy(this::authorize).isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("USER_DISABLED");
    }
    @Test void revokedOrgMembershipCannotUsePreviouslyAuthorizedRun() {
        identities.updateMembershipStatus(org,user,"DISABLED");
        assertThatThrownBy(this::authorize).isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("MEMBERSHIP_DISABLED");
    }
    @Test void localDelegationCannotBeReplayedInOidcMode() {
        jdbc.update("UPDATE ga_run_authorizations SET actor_source='LOCAL' WHERE run_id=?",run);
        mode.setMode("OIDC");
        assertThatThrownBy(this::authorize).isInstanceOf(AccessDeniedException.class);
    }
    @Test void taskModeRequiresRealTaskDespiteValidPersistedUser() {
        var subject=new GaExecutionAuthorization(actors,tasks,writes,new ExecutionOwnershipMode(true),mode,guard);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(tx->subject.lockAndAuthorize(run,1)))
                .isInstanceOf(TaskOwnershipLostException.class);
    }
    @Test void eventSubscriptionIsBoundToSubscriberAndClosesAfterRevocation() {
        int before=streams.subscriberCount();
        createBrowserSession();
        try(var actor=ActorContextHolder.bind(ActorContext.oidc(user,org));
            var browser=BrowserSessionContext.bind(sessionId)) {
            streams.subscribe(run,0,()->new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(0L));
        }
        assertThat(streams.subscriberCount()).isEqualTo(before+1);
        identities.updateMembershipStatus(org,user,"DISABLED");
        // There is no request or Worker actor on the catch-up thread.
        streams.catchUpAll();
        assertThat(streams.subscriberCount()).isEqualTo(before);
    }
    private void createBrowserSession() {
        sessionId=UUID.randomUUID().toString();
        long now=System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO spring_session(primary_id,session_id,creation_time,last_access_time,
                    max_inactive_interval,expiry_time) VALUES(?,?,?,?,1800,?)
                """,sessionId,sessionId,now,now,now+1800000);
        jdbc.update("INSERT INTO spring_session_attributes VALUES(?,'SPRING_SECURITY_CONTEXT',?)",
                sessionId,new byte[]{1});
    }
    @Test void logoutOnAnotherInstanceStopsEventCatchUpWithoutWorkerIdentity() {
        createBrowserSession();
        int before=streams.subscriberCount();
        try(var actor=ActorContextHolder.bind(ActorContext.oidc(user,org));
            var browser=BrowserSessionContext.bind(sessionId)) {
            streams.subscribe(run,0,()->new org.springframework.web.servlet.mvc.method.annotation.SseEmitter(0L));
        }
        jdbc.update("DELETE FROM spring_session WHERE session_id=?",sessionId);
        streams.catchUpAll();
        assertThat(streams.subscriberCount()).isEqualTo(before);
    }
    @Test void anotherOrgMemberCannotReadCancelOrSubscribeToPrivateRun() {
        UUID other=UUID.randomUUID();
        identities.insertUserIfAbsent(other,"Other private-run user");
        identities.upsertMembership(org,other,OrgRole.ADMIN);
        try(var actor=ActorContextHolder.bind(ActorContext.oidc(other,org))) {
            assertThatThrownBy(()->application.requireRun(run)).isInstanceOf(com.specagent.common.ApiException.class);
            assertThatThrownBy(()->application.cancelRun(run)).isInstanceOf(com.specagent.common.ApiException.class);
            assertThatThrownBy(()->streams.subscribe(run,0)).isInstanceOf(AccessDeniedException.class);
        } finally {
            jdbc.update("DELETE FROM organization_members WHERE user_id=?",other);
            jdbc.update("DELETE FROM app_users WHERE id=?",other);
        }
    }
    @Test void revokedStandaloneRunConvergesWithoutTaskOrConversationBody() {
        identities.updateMembershipStatus(org,user,"DISABLED");
        outbox.append(user,org);
        revoker.poll();
        assertThat(runs.findById(run).orElseThrow().status()).isEqualTo(GlobalAssistantRunStatus.CANCELLED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM global_assistant_run_events WHERE run_id=? AND type='RUN_CANCELLED'",
                Integer.class,run)).isEqualTo(1);
        outbox.append(user,org); // Redelivery must not duplicate the terminal event.
        revoker.poll();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM global_assistant_run_events WHERE run_id=? AND type='RUN_CANCELLED'",
                Integer.class,run)).isEqualTo(1);
    }
    @Test void membershipChangeThatRetainsDelegationDoesNotCancelStandaloneRun() {
        outbox.append(user,org);
        revoker.poll();
        assertThat(runs.findById(run).orElseThrow().status()).isEqualTo(GlobalAssistantRunStatus.CREATED);
    }
    @Test void revokedQueuedTaskConvergesWithRunAndEventWithoutReadingConversation() {
        UUID task=UUID.randomUUID();
        tasks.insert(task,RuntimeTaskFamily.GLOBAL_ASSISTANT,null,run,"ga_execution",null,null,3,1,
                new TaskActor(user,org,ActorSource.OIDC),java.util.List.of("ga-thread:"+thread));
        identities.updateMembershipStatus(org,user,"DISABLED");
        outbox.append(user,org);
        revoker.poll();
        assertThat(tasks.findById(task).orElseThrow().state()).isEqualTo(RuntimeTaskState.CANCELLED);
        assertThat(runs.findById(run).orElseThrow().status()).isEqualTo(GlobalAssistantRunStatus.CANCELLED);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM global_assistant_run_events WHERE run_id=? AND type='RUN_CANCELLED'",
                Integer.class,run)).isEqualTo(1);
        revoker.poll();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM global_assistant_run_events WHERE run_id=? AND type='RUN_CANCELLED'",
                Integer.class,run)).isEqualTo(1);
    }
}
