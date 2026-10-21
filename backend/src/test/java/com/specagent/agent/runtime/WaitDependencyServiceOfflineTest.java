package com.specagent.agent.runtime;

import com.specagent.agent.protocol.*;
import com.specagent.capability.*;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Host service + mocked storage/authority. No Spring context or SQL execution. */
class WaitDependencyServiceOfflineTest {
    final WaitDependencyRepository waits = mock(WaitDependencyRepository.class);
    final CapabilityInvocationRepository invocations = mock(CapabilityInvocationRepository.class);
    final CapabilityRegistry registry = mock(CapabilityRegistry.class);
    final DeferredCapabilityAdapter adapter = mock(DeferredCapabilityAdapter.class);
    final ProjectService projects = mock(ProjectService.class);
    final WaitDependencyService service = new WaitDependencyService(waits, invocations, registry, projects);

    AgentRequestEnvelope fixture() throws Exception {
        return AgentContracts.read(Files.readString(Path.of("../contracts/fixtures/agent-input-v4-wait.json")), AgentRequestEnvelope.class);
    }
    CapabilityInvocationRecord record(AgentRequestEnvelope request, CapabilityResult.Status status) {
        var d = request.pendingDependencies().dependencies().getFirst();
        return new CapabilityInvocationRecord(d.invocationId(), "key", request.snapshot().projectId(), d.ownerRunId(),
                d.capabilityId(), Map.of(), status, Map.of("content", Map.of("summary","completed")), Instant.EPOCH, null);
    }
    void setup(AgentRequestEnvelope request, CapabilityResult.Status status) {
        var record = record(request, status);
        when(waits.frozen(request.runId())).thenReturn(Optional.of(request.pendingDependencies()));
        when(invocations.findById(record.id())).thenReturn(Optional.of(record));
        when(registry.findAdapter(record.capabilityId())).thenReturn(Optional.of(adapter));
    }
    ActionProposal proposal(AgentRequestEnvelope request, String ref) {
        return new ActionProposal("WAIT", Map.of("dependencyRef",ref),UUID.fromString(request.snapshot().snapshotId()),
                request.snapshot().contextHash(),List.of(),UUID.randomUUID(),request.runId().toString(),List.of());
    }
    @Test void frozenPendingCanSubscribeAfterItHasAlreadyCompletedWithoutRequiringAnotherCallback() throws Exception {
        var request = fixture(); setup(request,CapabilityResult.Status.SUCCEEDED);
        var d = request.pendingDependencies().dependencies().getFirst();var proposal = proposal(request,d.invocationId().toString());
        service.subscribe(request,proposal);
        var order = inOrder(projects,waits,adapter);
        order.verify(projects).authorizeWrite(request.snapshot().projectId());
        order.verify(waits).requireScope(request);
        order.verify(waits).frozen(request.runId());
        order.verify(adapter).requireAuthorizedCompletion(record(request,CapabilityResult.Status.SUCCEEDED));
        order.verify(waits).requireOwner(d.invocationId(),request.pendingDependencies(),true);
        order.verify(waits).subscribe(request.pendingDependencies(),d.invocationId(),proposal.proposalId());
    }
    @Test void unknownDependencyCannotAcquireLocksOrSubscribe() throws Exception {
        var request=fixture();setup(request,CapabilityResult.Status.PENDING);
        assertThatThrownBy(()->service.subscribe(request,proposal(request,UUID.randomUUID().toString())))
                .hasMessage("WAIT_DEPENDENCY_NOT_PROJECTED");
        verify(waits,never()).requireOwner(any(),any(),anyBoolean());
        verify(waits,never()).subscribe(any(),any(),any());
    }
    @Test void revokedAuthorizationCannotReadmitFrozenPendingOrRegisterWait() throws Exception {
        var request=fixture();setup(request,CapabilityResult.Status.PENDING);
        doThrow(new IllegalStateException("revoked")).when(adapter).requireAuthorizedCompletion(any());
        assertThatThrownBy(()->service.freeze(request)).hasMessage("revoked");
        assertThatThrownBy(()->service.subscribe(request,proposal(request,request.pendingDependencies().dependencies().getFirst().invocationId().toString())))
                .hasMessage("revoked");
        verify(waits,never()).subscribe(any(),any(),any());
    }
    @Test void dependencyRemovedFromVisibleCatalogCannotBeReplayed() throws Exception {
        var request=fixture();setup(request,CapabilityResult.Status.PENDING);
        var s=request.snapshot();
        var hidden=new AgentInputSnapshot(s.snapshotId(),s.contextHash(),s.projectId(),s.routeId(),s.anchorNodeId(),
                s.routeContext(),s.lineage(),s.effectiveClaims(),s.metadata(),s.allowedSourceRefs(),List.of(),
                s.capabilityResults(),s.relations(),s.relatedNodes(),s.autonomy());
        var changed=new AgentRequestEnvelope(request.protocolVersion(),request.runId(),request.event(),hidden,
                request.capabilities(),request.decisionBudget(),request.actionEligibility(),request.pendingDependencies());
        assertThatThrownBy(()->service.freeze(changed)).hasMessage("WAIT_CAPABILITY_NOT_VISIBLE");
    }
    @Test void failedProjectAuthorizationPrecedesAllPersistence() throws Exception {
        var request=fixture();
        doThrow(new IllegalStateException("project denied")).when(projects).authorizeWrite(any());
        assertThatThrownBy(()->service.freeze(request)).hasMessage("project denied");
        verifyNoInteractions(waits, invocations, registry);
    }
    @Test void firstProjectionFreezesOnceAndReplayDoesNotRescanLivePendingRows() throws Exception {
        var request=fixture();setup(request,CapabilityResult.Status.PENDING);
        when(waits.frozen(request.runId())).thenReturn(Optional.empty(),Optional.of(request.pendingDependencies()));
        when(waits.candidates(request)).thenReturn(request.pendingDependencies().dependencies());
        when(waits.freeze(any())).thenReturn(true);
        assertThat(service.freeze(request)).isEqualTo(request.pendingDependencies());
        assertThat(service.freeze(request)).isEqualTo(request.pendingDependencies());
        verify(waits,times(1)).candidates(request);verify(waits,times(1)).freeze(any());
        verify(adapter,times(2)).requireAuthorizedCompletion(any());
    }

    @Test void resumedChildCarriesExactAuthorizedSettledResultOutsideRecentObservationWindow() throws Exception {
        var request=fixture();setup(request,CapabilityResult.Status.FAILED);
        when(waits.frozen(request.runId())).thenReturn(Optional.empty());
        var record=record(request,CapabilityResult.Status.FAILED);
        when(waits.resumedInvocation(request.runId())).thenReturn(Optional.of(record.id()));
        when(waits.freeze(any())).thenReturn(true);
        var frozen=service.freeze(request);
        assertThat(frozen.dependencies()).isEmpty();
        assertThat(frozen.completedResults()).hasSize(1);
        assertThat(frozen.completedResults().getFirst().invocationId()).isEqualTo(record.id().toString());
        assertThat(frozen.completedResults().getFirst().status()).isEqualTo("FAILED");
        assertThat(frozen.completedResults().getFirst().content()).containsEntry("summary","completed");
        verify(adapter).requireAuthorizedCompletion(record);
        verify(invocations,never()).findRecentCompleted(any(),anyInt());
    }

    @Test void foreignInvocationRecordCannotBeSubscribedEvenWhenFrozenViewLooksValid() throws Exception {
        var request=fixture();setup(request,CapabilityResult.Status.PENDING);var actual=record(request,CapabilityResult.Status.PENDING);
        when(invocations.findById(actual.id())).thenReturn(Optional.of(new CapabilityInvocationRecord(actual.id(),actual.invocationKey(),
                UUID.randomUUID(),actual.runId(),actual.capabilityId(),Map.of(),actual.status(),Map.of(),Instant.EPOCH,null)));
        assertThatThrownBy(()->service.subscribe(request,proposal(request,actual.id().toString()))).hasMessage("WAIT_DEPENDENCY_SCOPE_INVALID");
        verify(waits,never()).subscribe(any(),any(),any());
    }
}
