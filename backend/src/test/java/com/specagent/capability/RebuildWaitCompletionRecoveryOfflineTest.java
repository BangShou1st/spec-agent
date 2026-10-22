package com.specagent.capability;

import com.specagent.access.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Actual scanner -> runtime -> adapter authorization. Storage/CAS are simulated,
 * so these do not prove SQL transaction races or cross-process recovery. */
class RebuildWaitCompletionRecoveryOfflineTest {
    final UUID project=UUID.randomUUID(),org=UUID.randomUUID(),run=UUID.randomUUID(),id=UUID.randomUUID(),generation=UUID.randomUUID();
    final RebuildWaitRepository waits=mock(RebuildWaitRepository.class);
    final CapabilityInvocationRepository rows=mock(CapabilityInvocationRepository.class);
    final CapabilityRuntime runtime=new CapabilityRuntime(new CapabilityRegistry(List.of(new RetrievalAwaitGenerationCapability(waits))),rows);
    final LocalTransactions manager=new LocalTransactions();
    final ObjectProvider<CapabilityRuntime> provider=provider();
    @SuppressWarnings("unchecked") ObjectProvider<CapabilityRuntime> provider() {
        var p=(ObjectProvider<CapabilityRuntime>)mock(ObjectProvider.class);when(p.getObject()).thenReturn(runtime);return p;
    }
    CapabilityInvocationRecord record(CapabilityResult.Status status) {
        return new CapabilityInvocationRecord(id,"key",project,run,RetrievalAwaitGenerationCapability.ID,Map.of(),status,Map.of(),Instant.now(),null);
    }
    RebuildWaitCompletionRecovery scanner() { return new RebuildWaitCompletionRecovery(waits,rows,provider,manager); }
    void arrange(String state) {
        when(waits.pending(10)).thenReturn(List.of(id));when(rows.findById(id)).thenReturn(Optional.of(record(CapabilityResult.Status.PENDING)));
        when(rows.findByInvocationKey("key")).thenReturn(Optional.of(record(CapabilityResult.Status.PENDING)));
        when(waits.bound(id)).thenReturn(Optional.of(new RebuildWaitRepository.Task(generation,2,org,project,state)));
        when(waits.authorize(run,project)).thenReturn(ActorContext.oidc(UUID.randomUUID(),org));
    }
    @ParameterizedTest @ValueSource(strings={"READY","ACTIVE","FAILED","DISCARDED"})
    void committedBusinessOutcomeUsesExistingTerminalCas(String state) {
        arrange(state);scanner().recover(10);
        var result=ArgumentCaptor.forClass(CapabilityResult.class);verify(rows).completePending(eq(id),result.capture());
        assertThat(result.getValue().status()).isEqualTo(Set.of("READY","ACTIVE").contains(state)?CapabilityResult.Status.SUCCEEDED:CapabilityResult.Status.FAILED);
        assertThat(result.getValue().provenance()).containsEntry("readinessRevision",2L);
        assertThat(manager.commits).isEqualTo(1);
    }
    @Test void completionBeforeSubscriptionAndDuplicateDeliveryUseTheSameInvocation() {
        arrange("READY");when(rows.completePending(eq(id),any())).thenReturn(true,false);
        var scanner=scanner();scanner.recover(10);scanner.recover(10);
        verify(rows,times(2)).completePending(eq(id),any());
        // Real SQL/outbox winning-CAS behavior is covered by DeferredCapabilityRepositoryTest.
    }
    @Test void completionAfterTimeoutDoesNotChangeTimedOutInvocation() {
        arrange("READY");when(rows.findById(id)).thenReturn(Optional.of(record(CapabilityResult.Status.FAILED)));
        scanner().recover(10);verify(rows,never()).completePending(any(),any());
    }
    @Test void timeoutWinsBetweenReadAndTerminalCas() {
        arrange("READY");when(rows.completePending(eq(id),any())).thenReturn(false);
        scanner().recover(10);verify(rows).completePending(eq(id),any());assertThat(manager.commits).isEqualTo(1);
    }
    @Test void noOutcomeForBoundRevisionCannotConsumeOldCompletion() {
        arrange(null);scanner().recover(10);verify(rows,never()).completePending(any(),any());
    }
    @Test void completionFailureIsRetryableByFreshScannerWithoutAnyLiveCaller() {
        arrange("READY");when(provider.getObject()).thenThrow(new IllegalStateException("continuation temporarily unavailable")).thenReturn(runtime);
        scanner().recover(10);assertThat(manager.rollbacks).isEqualTo(1);verify(rows,never()).completePending(any(),any());
        scanner().recover(10);verify(rows).completePending(eq(id),any());assertThat(manager.commits).isEqualTo(1);
    }
    @Test void revocationCannotCompleteAndDoesNotPreventOtherCandidatesBeingScanned() {
        arrange("READY");UUID other=UUID.randomUUID();when(waits.pending(10)).thenReturn(List.of(id,other));
        when(rows.findById(other)).thenReturn(Optional.empty());
        when(waits.authorize(run,project)).thenThrow(AccessDeniedException.notVisible("revoked"));
        scanner().recover(10);verify(rows,never()).completePending(any(),any());verify(rows).findById(other);
        assertThat(manager.rollbacks).isEqualTo(1);
    }
    static class LocalTransactions extends AbstractPlatformTransactionManager {
        int commits,rollbacks;
        @Override protected Object doGetTransaction() {return new Object();}
        @Override protected void doBegin(Object tx,org.springframework.transaction.TransactionDefinition definition) {}
        @Override protected void doCommit(DefaultTransactionStatus status) {commits++;}
        @Override protected void doRollback(DefaultTransactionStatus status) {rollbacks++;}
    }
}
