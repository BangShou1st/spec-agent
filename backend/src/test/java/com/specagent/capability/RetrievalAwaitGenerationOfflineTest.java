package com.specagent.capability;

import com.specagent.access.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual production adapter/runtime, mocked storage only. No context or external worker. */
class RetrievalAwaitGenerationOfflineTest {
    final UUID project=UUID.randomUUID(),org=UUID.randomUUID(),run=UUID.randomUUID(),generation=UUID.randomUUID(),id=UUID.randomUUID();
    final ActorContext actor=ActorContext.oidc(UUID.randomUUID(),org);
    final RebuildWaitRepository waits=mock(RebuildWaitRepository.class);
    final RetrievalAwaitGenerationCapability adapter=new RetrievalAwaitGenerationCapability(waits);
    final CapabilityInvocation invocation=new CapabilityInvocation(id,"key",RetrievalAwaitGenerationCapability.ID,project,run,Map.of());
    RebuildWaitRepository.Task task(String state,long revision) { return new RebuildWaitRepository.Task(generation,revision,org,project,state); }
    CapabilityInvocationRecord record(CapabilityResult.Status status) {
        return new CapabilityInvocationRecord(id,"key",project,run,RetrievalAwaitGenerationCapability.ID,Map.of(),status,Map.of(),Instant.now(),null);
    }
    void authorized() { when(waits.authorize(run,project)).thenReturn(actor); }

    @ParameterizedTest @ValueSource(strings={"QUEUED","RUNNING"})
    void alreadyRequestedTaskBecomesConcretePendingBinding(String state) {
        authorized();var task=task(state,3);when(waits.select(project,org)).thenReturn(Optional.of(task));
        var result=adapter.invoke(invocation);
        assertThat(result.status()).isEqualTo(CapabilityResult.Status.PENDING);
        assertThat(result.provenance()).containsEntry("generationId",generation.toString()).containsEntry("readinessRevision",3L);
        verify(waits).bind(id,task);
    }
    @Test void alreadyReadyReturnsSynchronousSuccessWithoutSubmittingOrActivatingWork() {
        authorized();when(waits.select(project,org)).thenReturn(Optional.of(task("READY",0)));
        assertThat(adapter.invoke(invocation).status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
        verify(waits).bind(id,task("READY",0));
    }
    @Test void noTaskFailsWithoutRegisteringFictitiousDependency() {
        authorized();when(waits.select(project,org)).thenReturn(Optional.empty());
        assertThat(adapter.invoke(invocation).status()).isEqualTo(CapabilityResult.Status.FAILED);
        verify(waits,never()).bind(any(),any());
    }
    @ParameterizedTest @ValueSource(strings={"generationId","projectId","taskRef","readinessRevision"})
    void modelCannotSupplyAnyExternalTaskIdentity(String field) {
        assertThatThrownBy(()->adapter.requireAuthorizedInvocation(new CapabilityInvocation(id,"key",RetrievalAwaitGenerationCapability.ID,
                project,run,Map.of(field,generation.toString())))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(waits);
    }
    @Test void selectionCannotCrossProjectEvenIfRepositoryMisroutes() {
        authorized();when(waits.select(project,org)).thenReturn(Optional.of(new RebuildWaitRepository.Task(generation,0,org,UUID.randomUUID(),"RUNNING")));
        assertThatThrownBy(()->adapter.invoke(invocation)).isInstanceOf(AccessDeniedException.class);
        verify(waits,never()).bind(any(),any());
    }
    @Test void completionCannotCrossOrganization() {
        authorized();when(waits.bound(id)).thenReturn(Optional.of(new RebuildWaitRepository.Task(generation,0,UUID.randomUUID(),project,"READY")));
        assertThatThrownBy(()->adapter.requireAuthorizedCompletion(record(CapabilityResult.Status.PENDING))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void missingBindingIsNotAValidPendingDependency() {
        authorized();when(waits.bound(id)).thenReturn(Optional.empty());
        assertThatThrownBy(()->adapter.requireAuthorizedCompletion(record(CapabilityResult.Status.PENDING))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void revokedActorCannotRegisterOrComplete() {
        when(waits.authorize(run,project)).thenThrow(AccessDeniedException.notVisible("revoked"));
        assertThatThrownBy(()->adapter.invoke(invocation)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->adapter.requireAuthorizedCompletion(record(CapabilityResult.Status.PENDING))).isInstanceOf(AccessDeniedException.class);
        verify(waits,never()).bind(any(),any());
    }
    @Test void realRuntimeBindsBeforePendingAndReplayNeverSelectsANewerGeneration() {
        authorized();var task=task("RUNNING",7);when(waits.select(project,org)).thenReturn(Optional.of(task));
        var rows=mock(CapabilityInvocationRepository.class);var runtime=new CapabilityRuntime(new CapabilityRegistry(List.of(adapter)),rows);
        when(rows.claim(any())).thenReturn(true,false);when(rows.markPending(any(),any(),eq(3600L))).thenReturn(true);
        when(rows.matchesIdentity(any())).thenReturn(true);
        var first=runtime.invokeDeferredAllowed("key",RetrievalAwaitGenerationCapability.ID,project,run,Map.of());
        var saved=new CapabilityInvocationRecord(first.invocationId(),"key",project,run,RetrievalAwaitGenerationCapability.ID,
                Map.of(),CapabilityResult.Status.PENDING,Map.of(),Instant.now(),null);
        when(rows.findByInvocationKey("key")).thenReturn(Optional.of(saved));when(waits.bound(first.invocationId())).thenReturn(Optional.of(task));
        assertThat(runtime.invokeDeferredAllowed("key",RetrievalAwaitGenerationCapability.ID,project,run,Map.of()).invocationId()).isEqualTo(first.invocationId());
        verify(waits,times(1)).select(project,org);
        var order=inOrder(waits,rows);order.verify(waits).bind(first.invocationId(),task);order.verify(rows).markPending(eq(first.invocationId()),any(),eq(3600L));
        doThrow(AccessDeniedException.notVisible("revoked")).when(waits).authorize(run,project);
        assertThatThrownBy(()->runtime.invokeDeferredAllowed("key",RetrievalAwaitGenerationCapability.ID,project,run,Map.of())).isInstanceOf(AccessDeniedException.class);
    }
    @Test void recordedResultVisibilityReauthorizesStaticDeferredAdapter() {
        var registry=new CapabilityRegistry(List.of(adapter));
        assertThat(registry.hasRecordedResultAuthorization(RetrievalAwaitGenerationCapability.ID)).isTrue();
        when(waits.authorize(run,project)).thenThrow(AccessDeniedException.notVisible("revoked"));
        assertThatThrownBy(()->registry.requireAuthorizedRecordedResult(record(CapabilityResult.Status.SUCCEEDED))).isInstanceOf(AccessDeniedException.class);
    }
    @Test void internalCompletionCannotInventAnOutcomeForAnUnfinishedRealTask() {
        when(waits.bound(id)).thenReturn(Optional.of(task(null,2)));
        var invented=RetrievalAwaitGenerationCapability.result(id,"key",task("READY",2),CapabilityResult.Status.SUCCEEDED);
        assertThatThrownBy(()->adapter.validateCompletion(record(CapabilityResult.Status.PENDING),invented))
                .hasMessageContaining("no committed terminal outcome");
    }
    @Test void oldRevisionResultCannotBeReusedForNewRevisionInvocation() {
        when(waits.bound(id)).thenReturn(Optional.of(task("READY",3)));
        var old=RetrievalAwaitGenerationCapability.result(id,"key",task("READY",2),CapabilityResult.Status.SUCCEEDED);
        assertThatThrownBy(()->adapter.validateCompletion(record(CapabilityResult.Status.PENDING),old)).hasMessageContaining("does not match");
        assertThatCode(()->adapter.validateCompletion(record(CapabilityResult.Status.PENDING),
                RetrievalAwaitGenerationCapability.result(id,"key",task("READY",3),CapabilityResult.Status.SUCCEEDED))).doesNotThrowAnyException();
    }
    @Test void oldGenerationResultCannotWakeNewGenerationEvenWithMatchingInvocationId() {
        when(waits.bound(id)).thenReturn(Optional.of(task("READY",2)));
        var oldTask=new RebuildWaitRepository.Task(UUID.randomUUID(),2,org,project,"READY");
        assertThatThrownBy(()->adapter.validateCompletion(record(CapabilityResult.Status.PENDING),
                RetrievalAwaitGenerationCapability.result(id,"key",oldTask,CapabilityResult.Status.SUCCEEDED))).hasMessageContaining("does not match");
    }
}
