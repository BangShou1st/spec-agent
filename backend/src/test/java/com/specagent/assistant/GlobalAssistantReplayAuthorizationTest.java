package com.specagent.assistant;

import com.specagent.access.*;
import com.specagent.assistant.conversation.*;
import com.specagent.assistant.runtime.GlobalAssistantStreamService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GlobalAssistantReplayAuthorizationTest {
    private final UUID runId=UUID.randomUUID(), threadId=UUID.randomUUID();
    private final ActorContext actor=ActorContext.oidc(UUID.randomUUID(),UUID.randomUUID());
    private final GlobalAssistantRunEventRepository events=mock(GlobalAssistantRunEventRepository.class);
    private final GlobalAssistantRunRepository runs=mock(GlobalAssistantRunRepository.class);
    private final AuthorizationGuard guard=mock(AuthorizationGuard.class);
    private final BrowserSessionValidity sessions=mock(BrowserSessionValidity.class);
    private final GlobalAssistantStreamService streams=new GlobalAssistantStreamService(events,runs,guard,sessions);
    private final AtomicBoolean sessionActive=new AtomicBoolean(true), owned=new AtomicBoolean(true);
    private final GlobalAssistantRun run=mock(GlobalAssistantRun.class);

    @BeforeEach void setup() {
        when(guard.ownershipActor()).thenReturn(actor);
        when(run.threadId()).thenReturn(threadId);
        when(run.status()).thenReturn(GlobalAssistantRunStatus.RUNNING);
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(sessions.active("browser-session")).thenAnswer(ignored->sessionActive.get());
        doAnswer(ignored->{if(!owned.get()) throw AccessDeniedException.notVisible("Conversation not found");return null;})
                .when(guard).requireThreadOwnershipFor(actor,threadId);
        when(events.findAfter(eq(runId),anyInt(),eq(200))).thenReturn(List.of());
    }

    private void subscribe(Capture emitter) {
        try(var session=BrowserSessionContext.bind("browser-session")) {
            streams.subscribe(runId,0,()->emitter);
        }
    }
    private GlobalAssistantRunEvent event(int sequence,String type) {
        return new GlobalAssistantRunEvent(UUID.randomUUID(),runId,sequence,type,
                Map.of("text","private-event-"+sequence),Instant.now());
    }

    @Test void invalidSharedSessionRefusesBeforeCreatingEmitterOrReadingEvents() {
        sessionActive.set(false);
        var created=new AtomicBoolean();
        try(var session=BrowserSessionContext.bind("browser-session")) {
            assertThatThrownBy(()->streams.subscribe(runId,0,()->{created.set(true);return new Capture();}))
                    .isInstanceOf(AccessDeniedException.class)
                    .extracting(ex->((AccessDeniedException)ex).reasonCode()).isEqualTo(AccessReason.UNAUTHENTICATED);
        }
        assertThat(created.get()).isFalse();verifyNoInteractions(events);
    }

    @Test void logoutDuringInitialReplayStopsBeforeSecondPrivateEvent() {
        when(events.findAfter(runId,0,200)).thenReturn(List.of(event(1,"ASSISTANT_DELTA"),event(2,"ASSISTANT_DELTA")));
        var emitter=new Capture();emitter.afterSend=()->sessionActive.set(false);
        subscribe(emitter);
        assertThat(emitter.sequences).containsExactly(1);
        assertThat(emitter.completed).isTrue();assertThat(streams.subscriberCount()).isZero();
    }

    @Test void revokedOwnershipDuringCatchUpStopsBeforeSecondPrivateEvent() {
        var emitter=new Capture();subscribe(emitter);
        when(events.findAfter(runId,0,200)).thenReturn(List.of(event(1,"ASSISTANT_DELTA"),event(2,"ASSISTANT_DELTA")));
        emitter.afterSend=()->owned.set(false);
        assertThat(streams.catchUpAll()).isEqualTo(1);
        assertThat(emitter.sequences).containsExactly(1);assertThat(emitter.completed).isTrue();
        assertThat(streams.subscriberCount()).isZero();
    }

    @Test void sessionReadFailureClosesSubscriptionWithoutFailingRun() {
        var emitter=new Capture();subscribe(emitter);
        when(sessions.active("browser-session")).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("session read failed"));
        assertThat(streams.catchUpAll()).isZero();assertThat(emitter.completed).isTrue();
        verify(runs,never()).terminalize(any(),any(),any());
    }

    @Test void localPublicationDeliversUnseenCrossInstanceEventsInOrder() {
        var emitter=new Capture();subscribe(emitter);
        var earlier=event(1,"ASSISTANT_DELTA");var local=event(2,"ASSISTANT_DELTA");
        when(events.findAfter(runId,0,200)).thenReturn(List.of(earlier,local));
        streams.publish(runId,local);
        streams.catchUpAll();
        assertThat(emitter.sequences).containsExactly(1,2);
        assertThat(streams.catchUpAll()).isZero();assertThat(emitter.sequences).containsExactly(1,2);
        verify(events,never()).findAfter(any(),anyInt());
    }

    @org.junit.jupiter.api.AfterEach void stopDelivery() { streams.stopDelivery(); }

    @Test void terminalRunWithMoreThanOneBatchDoesNotCloseBeforeBacklogIsDrained() {
        when(run.status()).thenReturn(GlobalAssistantRunStatus.COMPLETED);
        List<GlobalAssistantRunEvent> history=java.util.stream.IntStream.rangeClosed(1,201)
                .mapToObj(i->event(i,i==201?"RUN_COMPLETED":"ASSISTANT_DELTA")).toList();
        when(events.findAfter(eq(runId),anyInt(),eq(200))).thenAnswer(call->{
            int cursor=call.getArgument(1);return history.stream().filter(e->e.sequence()>cursor).limit(200).toList();
        });
        var emitter=new Capture();subscribe(emitter);
        assertThat(emitter.sequences).hasSize(200);assertThat(emitter.completed).isFalse();
        assertThat(streams.catchUpAll()).isEqualTo(1);
        assertThat(emitter.sequences).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1,201).boxed().toList());
        assertThat(emitter.completed).isTrue();assertThat(streams.subscriberCount()).isZero();
    }

    @Test void wrongRunPublicationCannotForwardAnotherRunsPayload() {
        var emitter=new Capture();subscribe(emitter);
        var other=new GlobalAssistantRunEvent(UUID.randomUUID(),UUID.randomUUID(),1,"ASSISTANT_DELTA",Map.of("text","other-private"),Instant.now());
        assertThatThrownBy(()->streams.publish(runId,other)).isInstanceOf(IllegalArgumentException.class);
        assertThat(emitter.sequences).isEmpty();
    }

    @Test void failedEventReadClosesStreamAndDoesNotFailBusinessRun() {
        var emitter=new Capture();subscribe(emitter);
        when(events.findAfter(runId,0,200)).thenThrow(new org.springframework.dao.DataAccessResourceFailureException("event read failed"));
        assertThat(streams.catchUpAll()).isZero();assertThat(emitter.completed).isTrue();
        verify(runs,never()).terminalize(any(),any(),any());
    }

    @Test void manyCompletedRunsLeaveNoHistoricalSubscriptionOrLockBuckets() {
        when(run.status()).thenReturn(GlobalAssistantRunStatus.COMPLETED);
        try(var session=BrowserSessionContext.bind("browser-session")) {
            for(int i=0;i<100;i++) {
                UUID id=UUID.randomUUID();
                when(runs.findById(id)).thenReturn(Optional.of(run));
                when(events.findAfter(id,0,200)).thenReturn(List.of());
                var emitter=new Capture();streams.subscribe(id,0,()->emitter);
                assertThat(emitter.completed).isTrue();
            }
        }
        assertThat(streams.subscriberCount()).isZero();
        assertThat(streams.subscriptionBucketCount()).isZero();
    }

    @Test void concurrentLocalPublishAndPollNeverDuplicateOrSkipTerminalBacklog() throws Exception {
        var emitter=new Capture();subscribe(emitter);
        List<GlobalAssistantRunEvent> history=java.util.stream.IntStream.rangeClosed(1,51)
                .mapToObj(i->event(i,i==51?"RUN_COMPLETED":"ASSISTANT_DELTA")).toList();
        when(events.findAfter(eq(runId),anyInt(),eq(200))).thenAnswer(call->{
            int cursor=call.getArgument(1);return history.stream().filter(e->e.sequence()>cursor).limit(200).toList();
        });
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            var published=pool.submit(()->{start.await();streams.publish(runId,history.getLast());return null;});
            var polled=pool.submit(()->{start.await();return streams.catchUpAll();});
            start.countDown();published.get(2,java.util.concurrent.TimeUnit.SECONDS);polled.get(2,java.util.concurrent.TimeUnit.SECONDS);
            assertThat(emitter.sequences).containsExactlyElementsOf(java.util.stream.IntStream.rangeClosed(1,51).boxed().toList());
            assertThat(emitter.completed).isTrue();
            assertThat(streams.subscriberCount()).isZero();assertThat(streams.subscriptionBucketCount()).isZero();
        } finally {pool.shutdownNow();}
    }

    private static final class Capture extends SseEmitter {
        final List<Integer> sequences=new ArrayList<>();boolean completed;Runnable afterSend=()->{};
        Capture(){super(0L);}
        @Override public void send(SseEventBuilder event) {
            for(var item:event.build()) if(item.getData() instanceof Map<?,?> map)
                sequences.add(((Number)map.get("sequence")).intValue());
            afterSend.run();
        }
        @Override public void complete(){completed=true;}
    }
}
