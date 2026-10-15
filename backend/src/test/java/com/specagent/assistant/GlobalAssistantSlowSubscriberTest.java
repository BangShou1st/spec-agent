package com.specagent.assistant;

import com.specagent.access.*;
import com.specagent.assistant.conversation.*;
import com.specagent.assistant.runtime.GlobalAssistantStreamService;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GlobalAssistantSlowSubscriberTest {
    @Test void saturatedDeliveryQueueRemovesOverflowWithoutCallingSocketCompletionOnPublisher() throws Exception {
        UUID runId=UUID.randomUUID(), threadId=UUID.randomUUID();
        var events=mock(GlobalAssistantRunEventRepository.class);
        var runs=mock(GlobalAssistantRunRepository.class);
        var guard=mock(AuthorizationGuard.class);
        var sessions=mock(BrowserSessionValidity.class);
        var actor=ActorContext.oidc(UUID.randomUUID(),UUID.randomUUID());
        var run=mock(GlobalAssistantRun.class);
        when(run.threadId()).thenReturn(threadId);
        when(run.status()).thenReturn(GlobalAssistantRunStatus.RUNNING);
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(guard.ownershipActor()).thenReturn(actor);
        when(sessions.active("test-session")).thenReturn(true);
        var ready=new AtomicBoolean();
        var event=new GlobalAssistantRunEvent(UUID.randomUUID(),runId,1,"ANSWER_DELTA",Map.of(),Instant.now());
        when(events.findAfter(eq(runId),anyInt(),eq(200))).thenAnswer(call -> ready.get()?List.of(event):List.of());
        var entered=new CountDownLatch(4);var unblock=new CountDownLatch(1);
        var streams=new GlobalAssistantStreamService(events,runs,guard,sessions,mock(GaSourceDependencyGuard.class));
        try {
            try(var context=BrowserSessionContext.bind("test-session")) {
                for(int i=0;i<140;i++) streams.subscribe(runId,0,()->new SseEmitter(30_000L) {
                    @Override public void send(SseEventBuilder builder) throws java.io.IOException {
                        entered.countDown();
                        try { unblock.await(5,TimeUnit.SECONDS); }
                        catch(InterruptedException ex) {Thread.currentThread().interrupt();throw new java.io.IOException(ex);}
                    }
                    @Override public void complete() {throw new AssertionError("Publisher must not call potentially blocking complete on overflow");}
                });
            }
            ready.set(true);
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2),()->streams.publish(runId,event));
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(streams.subscriberCount()).as("4 active deliveries + 128 queued; excess subscriptions removed").isEqualTo(132);
        } finally {unblock.countDown();streams.stopDelivery();}
    }

    @Test void blockedSocketDoesNotBlockCommitPollerOrAnotherSubscriberOfSameRun() throws Exception {
        UUID runId=UUID.randomUUID(), threadId=UUID.randomUUID();
        var actor=ActorContext.oidc(UUID.randomUUID(),UUID.randomUUID());
        var events=mock(GlobalAssistantRunEventRepository.class);
        var runs=mock(GlobalAssistantRunRepository.class);
        var guard=mock(AuthorizationGuard.class);
        var sessions=mock(BrowserSessionValidity.class);
        var run=mock(GlobalAssistantRun.class);
        when(run.threadId()).thenReturn(threadId);
        when(run.status()).thenReturn(GlobalAssistantRunStatus.RUNNING);
        when(runs.findById(runId)).thenReturn(Optional.of(run));
        when(guard.ownershipActor()).thenReturn(actor);
        when(sessions.active("test-session")).thenReturn(true);
        var ready=new AtomicBoolean();
        var event=new GlobalAssistantRunEvent(UUID.randomUUID(),runId,1,"ANSWER_DELTA",Map.of("text","private"),Instant.now());
        when(events.findAfter(eq(runId),anyInt(),eq(200))).thenAnswer(call ->
                ready.get() && (int)call.getArgument(1)==0 ? List.of(event) : List.of());
        var entered=new CountDownLatch(1);var unblock=new CountDownLatch(1);var fastSent=new CountDownLatch(1);
        var slow=new SseEmitter(0L) {
            @Override public void send(SseEventBuilder builder) throws java.io.IOException {
                entered.countDown();
                try { if(!unblock.await(5,TimeUnit.SECONDS)) throw new java.io.IOException("test send timeout"); }
                catch(InterruptedException ex) { Thread.currentThread().interrupt();throw new java.io.IOException(ex); }
            }
        };
        var fast=new SseEmitter(0L) {
            @Override public void send(SseEventBuilder builder) { fastSent.countDown(); }
        };
        var streams=new GlobalAssistantStreamService(events,runs,guard,sessions,mock(GaSourceDependencyGuard.class));
        try {
            try(var context=BrowserSessionContext.bind("test-session")) {
                streams.subscribe(runId,0,()->slow);streams.subscribe(runId,0,()->fast);
            }
            ready.set(true);
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofMillis(500),()->streams.publish(runId,event));
            assertThat(entered.await(2,TimeUnit.SECONDS)).isTrue();
            assertThat(fastSent.await(2,TimeUnit.SECONDS)).as("same-Run healthy subscriber progresses before slow send returns").isTrue();
            org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2),()->{
                for(int i=0;i<10000;i++) streams.scheduleCatchUpAll();
            });
            verify(runs,never()).terminalize(any(),any(),any());
        } finally {unblock.countDown();streams.stopDelivery();}
    }
}
