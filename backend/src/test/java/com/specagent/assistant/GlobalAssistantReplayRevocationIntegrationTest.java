package com.specagent.assistant;

import com.specagent.access.*;
import com.specagent.access.identity.IdentityRepository;
import com.specagent.assistant.conversation.*;
import com.specagent.assistant.runtime.GlobalAssistantStreamService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Real shared-session/permission rows; identity is bound explicitly, not an IdP/browser acceptance claim. */
@SpringBootTest
@ActiveProfiles("test")
class GlobalAssistantReplayRevocationIntegrationTest {
    @Autowired IdentityRepository identities;
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired GlobalAssistantRunEventRepository events;
    @Autowired GlobalAssistantRunRepository runs;
    @Autowired AuthorizationGuard guard;
    @Autowired BrowserSessionValidity sessions;
    @Autowired GaSourceDependencyGuard sourceDependencies;
    @Autowired JdbcTemplate jdbc;
    private final List<Fixture> touched=new ArrayList<>();
    private record Fixture(ActorContext actor,UUID thread,UUID run,String session) { }

    private Fixture fixture() {
        UUID org=UUID.randomUUID(),user=UUID.randomUUID();
        identities.insertOrganizationIfAbsent(org,"SSE isolation fixture","STANDARD");
        identities.insertUserIfAbsent(user,"SSE owner");identities.upsertMembership(org,user,OrgRole.OWNER);
        var actor=ActorContext.oidc(user,org);
        GlobalAssistantThread thread;GlobalAssistantRun run;
        try(var bound=ActorContextHolder.bind(actor)) {
            thread=conversations.createThread();run=conversations.createRun(thread.id(),"test","test","test");
        }
        String primary=UUID.randomUUID().toString(),session=UUID.randomUUID().toString();long now=System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO spring_session(primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name)
                VALUES(?,?,?,?,?,?,?)
                """,primary,session,now,now,1800,now+1800000,user.toString());
        jdbc.update("INSERT INTO spring_session_attributes(session_primary_id,attribute_name,attribute_bytes) VALUES(?,?,?)",
                primary,"SPRING_SECURITY_CONTEXT",new byte[]{1});
        var fixture=new Fixture(actor,thread.id(),run.id(),session);touched.add(fixture);return fixture;
    }

    private GlobalAssistantStreamService instance() {return new GlobalAssistantStreamService(events,runs,guard,sessions,sourceDependencies);}
    private void subscribe(GlobalAssistantStreamService streams,Fixture f,Capture emitter) {
        try(var actor=ActorContextHolder.bind(f.actor());var session=BrowserSessionContext.bind(f.session())) {
            streams.subscribe(f.run(),0,()->emitter);
        }
    }

    @Test void deletingSharedSessionDuringInitialReplayClosesBeforeSecondEvent() {
        var f=fixture();events.append(f.run(),"ASSISTANT_DELTA",Map.of("text","first"));
        events.append(f.run(),"ASSISTANT_DELTA",Map.of("text","private second"));
        var streams=instance();var emitter=new Capture();
        emitter.afterFirst=()->jdbc.update("DELETE FROM spring_session WHERE session_id=?",f.session());
        subscribe(streams,f,emitter);
        assertThat(emitter.sequences).containsExactly(1);assertThat(emitter.completed).isTrue();
        assertThat(streams.subscriberCount()).isZero();
        assertThat(runs.findById(f.run()).orElseThrow().status()).isEqualTo(GlobalAssistantRunStatus.CREATED);
    }

    @Test void membershipRevocationDuringCatchUpAffectsBothApiServiceInstances() {
        var f=fixture();var first=instance();var second=instance();var a=new Capture();var b=new Capture();
        subscribe(first,f,a);subscribe(second,f,b);
        events.append(f.run(),"ASSISTANT_DELTA",Map.of("text","first"));
        events.append(f.run(),"ASSISTANT_DELTA",Map.of("text","private second"));
        a.afterFirst=()->identities.updateMembershipStatus(f.actor().orgId(),f.actor().userId(),"DISABLED");
        assertThat(first.catchUpAll()).isEqualTo(1);assertThat(second.catchUpAll()).isZero();
        assertThat(a.sequences).containsExactly(1);assertThat(b.sequences).isEmpty();
        assertThat(a.completed).isTrue();assertThat(b.completed).isTrue();
        assertThat(first.subscriberCount()+second.subscriberCount()).isZero();
    }

    @Test void localPublicationCannotSkipEventCommittedByDifferentInstance() {
        var f=fixture();var streams=instance();var emitter=new Capture();subscribe(streams,f,emitter);
        events.append(f.run(),"ASSISTANT_DELTA",Map.of("text","other instance"));
        var local=events.append(f.run(),"ASSISTANT_DELTA",Map.of("text","local publication"));
        streams.publish(f.run(),local);
        streams.catchUpAll();
        assertThat(emitter.sequences).containsExactly(1,2);
        assertThat(streams.catchUpAll()).isZero();assertThat(emitter.sequences).containsExactly(1,2);
        emitter.complete();
    }

    @Test void completedBacklogOverBatchLimitIsDeliveredFullyBeforeClosing() {
        var f=fixture();
        for(int i=1;i<=205;i++) events.append(f.run(),"ASSISTANT_DELTA",Map.of("text","event-"+i));
        events.append(f.run(),"RUN_COMPLETED",Map.of());runs.terminalize(f.run(),GlobalAssistantRunStatus.COMPLETED,null);
        var streams=instance();var emitter=new Capture();subscribe(streams,f,emitter);
        assertThat(emitter.sequences).hasSize(200);assertThat(emitter.completed).isFalse();
        assertThat(streams.catchUpAll()).isEqualTo(6);
        assertThat(emitter.sequences).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1,206).boxed().toList());
        assertThat(emitter.completed).isTrue();assertThat(streams.subscriberCount()).isZero();
    }

    @AfterEach void cleanup() {
        for(var f:touched) {
            jdbc.update("DELETE FROM spring_session WHERE session_id=?",f.session());
            jdbc.update("DELETE FROM global_assistant_run_events WHERE run_id=?",f.run());
            jdbc.update("DELETE FROM global_assistant_messages WHERE thread_id=?",f.thread());
            jdbc.update("DELETE FROM global_assistant_runs WHERE thread_id=?",f.thread());
            jdbc.update("DELETE FROM global_assistant_threads WHERE id=?",f.thread());
            jdbc.update("DELETE FROM organization_members WHERE org_id=?",f.actor().orgId());
            jdbc.update("DELETE FROM organizations WHERE id=?",f.actor().orgId());
            jdbc.update("DELETE FROM app_users WHERE id=?",f.actor().userId());
        }
    }

    private static final class Capture extends SseEmitter {
        final List<Integer> sequences=new ArrayList<>();boolean completed;Runnable afterFirst=()->{};
        Capture(){super(0L);}
        @Override public void send(SseEventBuilder builder) {
            for(var item:builder.build()) if(item.getData() instanceof Map<?,?> map)
                sequences.add(((Number)map.get("sequence")).intValue());
            if(sequences.size()==1) afterFirst.run();
        }
        @Override public void complete(){completed=true;}
    }
}
