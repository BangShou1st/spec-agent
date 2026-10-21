package com.specagent.agent.runtime;

import com.specagent.agent.policy.AgentProposalService;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.capability.CapabilityInvocationRepository;
import com.specagent.workspace.node.NodeRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Deterministic durable-read simulations with the real coordinator, not process/DB recovery. */
class WaitLifecycleOfflineTest {
    @Test void permanentRevocationInvalidatesWaitAfterEvaluationTransactionHasFailed() {
        var checks=mock(ContinuationCheckRepository.class);
        var coordinator=mock(ContinuationCoordinator.class);
        var manager=new LocalTransactions();
        var tx=new org.springframework.transaction.support.TransactionTemplate(manager);
        var subscriptions=mock(WaitDependencyRepository.class);
        doThrow(com.specagent.access.AccessDeniedException.notVisible("revoked")).when(coordinator).continueIfEligible(any());
        when(subscriptions.invalidateDenied(any())).thenReturn(1);
        var dispatcher=new ContinuationDispatchService(checks,coordinator,tx);dispatcher.setWaitSubscriptions(subscriptions);
        UUID run=UUID.randomUUID();
        assertThatCode(()->dispatcher.process(new ContinuationCheck(run,3))).doesNotThrowAnyException();
        assertThat(manager.begins).isEqualTo(2);
        assertThat(manager.rollbacks).isEqualTo(1);
        assertThat(manager.commits).isEqualTo(1);
        verify(subscriptions).invalidateDenied(run);verify(checks).markProcessed(run,3);
    }
    /** Actual Spring transaction callbacks, with zero resources or connections. */
    static class LocalTransactions extends org.springframework.transaction.support.AbstractPlatformTransactionManager {
        int begins, commits, rollbacks;
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction,org.springframework.transaction.TransactionDefinition definition) { begins++; }
        @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) { commits++; }
        @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { rollbacks++; }
    }
    @Test void unknownAuthorizationScopeRetainsFailureAndDoesNotCancelWait() {
        var checks=mock(ContinuationCheckRepository.class);var coordinator=mock(ContinuationCoordinator.class);
        var subscriptions=mock(WaitDependencyRepository.class);
        var tx=new org.springframework.transaction.support.TransactionTemplate(new LocalTransactions());
        var failure=com.specagent.access.AccessDeniedException.forbidden(com.specagent.access.AccessReason.AUTHORIZATION_SCOPE_UNKNOWN,"scope unavailable");
        doThrow(failure).when(coordinator).continueIfEligible(any());
        var dispatcher=new ContinuationDispatchService(checks,coordinator,tx);dispatcher.setWaitSubscriptions(subscriptions);
        assertThatThrownBy(()->dispatcher.process(new ContinuationCheck(UUID.randomUUID(),1))).isSameAs(failure);
        verifyNoInteractions(checks,subscriptions);
    }

    final UUID id=UUID.randomUUID();
    final AgentRunService runs=mock(AgentRunService.class);
    final AgentRunRepository rows=mock(AgentRunRepository.class);
    final AgentRun parent=mock(AgentRun.class);
    final RunService enqueue=mock(RunService.class);
    final WaitDependencyRepository waits=mock(WaitDependencyRepository.class);
    ContinuationCoordinator restartCoordinator() {
        when(parent.id()).thenReturn(id);when(parent.status()).thenReturn(AgentRunStatus.COMPLETED);
        when(runs.getRun(id)).thenReturn(Optional.of(parent));
        var coordinator=new ContinuationCoordinator(runs,rows,mock(AgentRunEventService.class),mock(AgentProposalService.class),
                mock(CapabilityInvocationRepository.class),mock(NodeRepository.class),new LoopProperties(),enqueue);
        coordinator.setWaitDependencies(waits);return coordinator;
    }
    @Test void waitingNeverCreatesChildButReadyAfterReconstructionResumesOnlyOnce() {
        var first=restartCoordinator();when(waits.state(id)).thenReturn(Optional.of("WAITING"));
        assertThat(first.evaluate(id).verdict()).isEqualTo(ContinuationVerdict.PARKED_DEPENDENCY);
        assertThat(first.continueIfEligible(id)).isEmpty();verifyNoInteractions(enqueue);
        var reconstructed=restartCoordinator();when(waits.state(id)).thenReturn(Optional.of("READY"));
        var child=mock(AgentRun.class);when(enqueue.createContinueRun(parent)).thenReturn(new AgentRunService.CreateResult(child,true));
        assertThat(reconstructed.continueIfEligible(id)).contains(child);
        when(rows.findChildByParentRunId(id)).thenReturn(Optional.of(child));
        assertThat(restartCoordinator().continueIfEligible(id)).contains(child);
        verify(enqueue,times(1)).createContinueRun(parent);
    }
    @Test void invalidatedWaitStopsAndCannotCreateAnUnrelatedContinuation() {
        var coordinator=restartCoordinator();when(waits.state(id)).thenReturn(Optional.of("INVALIDATED"));
        assertThat(coordinator.evaluate(id).verdict()).isEqualTo(ContinuationVerdict.NO_EFFECT);
        assertThat(coordinator.continueIfEligible(id)).isEmpty();verifyNoInteractions(enqueue);
    }
    @Test void failedSubscriberCannotBeRevivedEvenIfDependencyIsReady() {
        var coordinator=restartCoordinator();when(parent.status()).thenReturn(AgentRunStatus.FAILED);
        assertThat(coordinator.evaluate(id).verdict()).isEqualTo(ContinuationVerdict.FAILED);
        verifyNoInteractions(waits,enqueue);
    }
    @Test void currentAuthorizationFailureDoesNotBecomeASuccessfulWakeup() {
        var coordinator=restartCoordinator();when(waits.state(id)).thenReturn(Optional.of("READY"));
        when(enqueue.createContinueRun(parent)).thenThrow(new IllegalStateException("project revoked"));
        assertThatThrownBy(()->coordinator.continueIfEligible(id)).hasMessage("project revoked");
        // Dispatcher transaction must retain the pending generation on this exception;
        // PostgreSQL rollback and actual cross-process replay are not simulated here.
    }
}
