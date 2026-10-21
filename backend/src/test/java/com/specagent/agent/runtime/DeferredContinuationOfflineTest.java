package com.specagent.agent.runtime;

import com.specagent.agent.policy.AgentProposalService;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.capability.*;
import com.specagent.workspace.node.NodeRepository;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Runs the real coordinator, using mock durable row reads. No worker/application/DB starts. */
class DeferredContinuationOfflineTest {
    @Test void timeoutBatchFailureDoesNotStarveExistingContinuationChecks() {
        var checks = mock(ContinuationCheckRepository.class);
        var transaction = mock(org.springframework.transaction.support.TransactionTemplate.class);
        var coordinator = mock(ContinuationCoordinator.class);
        var invocations = mock(CapabilityInvocationRepository.class);
        UUID owner = UUID.randomUUID();
        when(checks.claimPending(10, null, 60)).thenReturn(List.of(new ContinuationCheck(owner, 7)));
        when(invocations.expirePending(10)).thenThrow(new IllegalStateException("timeout batch failed"));
        doAnswer(call -> {
            java.util.function.Consumer<org.springframework.transaction.TransactionStatus> action = call.getArgument(0);
            action.accept(mock(org.springframework.transaction.TransactionStatus.class));
            return null;
        }).when(transaction).executeWithoutResult(any());
        var dispatcher = new ContinuationDispatchService(checks, coordinator, transaction);
        dispatcher.setDeferredInvocations(invocations);

        assertThatCode(() -> dispatcher.recoverPending(10)).doesNotThrowAnyException();
        verify(coordinator).continueIfEligible(owner);
        verify(checks).markProcessed(owner, 7);
        verify(invocations).expirePending(10);
    }

    @Test void dependencyWakeupCannotBeLostWhenParentTerminalizationIsNotYetVisible() {
        var jdbc = mock(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class);
        new ContinuationCheckRepository(jdbc).completed(UUID.randomUUID());
        verify(jdbc).update(org.mockito.ArgumentMatchers.argThat(sql ->
                        sql.contains("i.project_id = r.project_id")
                        // Completion's snapshot may still see RUNNING while terminalization
                        // commits and its consumer parks on the uncommitted PENDING result.
                        // Every matching durable owner needs a generation, irrespective of status.
                        && !sql.contains("r.status")
                        && sql.contains("request_generation = agent_run_continuation_checks.request_generation + 1")),
                org.mockito.ArgumentMatchers.<String, Object>anyMap());
    }
    final UUID runId = UUID.randomUUID();
    final AgentRunService runs = mock(AgentRunService.class);
    final AgentRunRepository runRepository = mock(AgentRunRepository.class);
    final AgentRunEventService events = mock(AgentRunEventService.class);
    final AgentProposalService proposals = mock(AgentProposalService.class);
    final CapabilityInvocationRepository invocations = mock(CapabilityInvocationRepository.class);
    final NodeRepository nodes = mock(NodeRepository.class);
    final RunService runService = mock(RunService.class);
    final AgentRun parent = mock(AgentRun.class);

    ContinuationCoordinator coordinator() {
        when(parent.id()).thenReturn(runId);
        when(parent.status()).thenReturn(AgentRunStatus.COMPLETED);
        when(runs.getRun(runId)).thenReturn(Optional.of(parent));
        return new ContinuationCoordinator(runs, runRepository, events, proposals, invocations,
                nodes, new LoopProperties(), runService);
    }

    CapabilityInvocationRecord record(CapabilityResult.Status status) {
        return new CapabilityInvocationRecord(UUID.randomUUID(), "key", UUID.randomUUID(), runId,
                "probe", Map.of(), status, Map.of(), Instant.EPOCH, null);
    }

    @Test void registeredPendingParksEvenWhenAnotherInvocationHasCompleted() {
        var coordinator = coordinator();
        when(invocations.findByRunId(runId)).thenReturn(List.of(record(CapabilityResult.Status.SUCCEEDED),
                record(CapabilityResult.Status.PENDING)));
        assertThat(coordinator.evaluate(runId).verdict()).isEqualTo(ContinuationVerdict.PARKED_DEPENDENCY);
        assertThat(coordinator.continueIfEligible(runId)).isEmpty();
        verifyNoInteractions(runService);
    }

    @Test void completionOrTimeoutCreatesAChildAndRedeliveryReturnsTheSameChild() {
        for (var status : List.of(CapabilityResult.Status.SUCCEEDED, CapabilityResult.Status.FAILED)) {
            var coordinator = coordinator();
            when(runRepository.findChildByParentRunId(runId)).thenReturn(Optional.empty());
            when(invocations.findByRunId(runId)).thenReturn(List.of(record(status)));
            var child = mock(AgentRun.class);
            when(runService.createContinueRun(parent)).thenReturn(new AgentRunService.CreateResult(child, true));
            assertThat(coordinator.continueIfEligible(runId)).contains(child);
            // After a restart/redelivery the existing single-child row is authoritative.
            when(runRepository.findChildByParentRunId(runId)).thenReturn(Optional.of(child));
            assertThat(coordinator.continueIfEligible(runId)).contains(child);
        }
        verify(runService, times(2)).createContinueRun(parent);
    }

    @Test void oldRunningCrashWindowNeverClaimsToBeRecoverablePending() {
        var coordinator = coordinator();
        when(invocations.findByRunId(runId)).thenReturn(List.of(record(CapabilityResult.Status.RUNNING)));
        assertThat(coordinator.evaluate(runId).verdict()).isEqualTo(ContinuationVerdict.NO_EFFECT);
    }

    @Test void failedOwnerCannotBeReactivatedByALateCompletion() {
        var coordinator = coordinator();
        when(parent.status()).thenReturn(AgentRunStatus.FAILED);
        assertThat(coordinator.evaluate(runId).verdict()).isEqualTo(ContinuationVerdict.FAILED);
        verifyNoInteractions(invocations, runService);
    }
}
