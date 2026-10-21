package com.specagent.capability;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real runtime/registry with mock persistence and a scripted authorized adapter. No external jobs. */
class DeferredCapabilityRuntimeTest {
    final CapabilityInvocationRepository repository = mock(CapabilityInvocationRepository.class);
    final DeferredCapabilityAdapter adapter = mock(DeferredCapabilityAdapter.class);
    final UUID project = UUID.randomUUID(), run = UUID.randomUUID();

    CapabilityRuntime runtime() {
        when(adapter.descriptor()).thenReturn(new CapabilityDescriptor("deferred.probe", "1", "fixture",
                Map.of(), Map.of(), true, SideEffectClass.NONE, List.of(), List.of()));
        when(adapter.completionTimeout()).thenReturn(Duration.ofSeconds(60));
        return new CapabilityRuntime(new CapabilityRegistry(List.of(adapter)), repository);
    }

    CapabilityResult terminal(UUID id) {
        return new CapabilityResult(id, "key", "deferred.probe", CapabilityResult.Status.SUCCEEDED,
                Map.of("evidence", "done"), List.of(), Map.of(), List.of());
    }

    CapabilityInvocationRecord pending(UUID id) {
        return new CapabilityInvocationRecord(id, "key", project, run, "deferred.probe", Map.of(),
                CapabilityResult.Status.PENDING, Map.of(), Instant.now(), null);
    }

    @Test void legacyAndApplicationCallersCannotSubmitDeferredJobs() {
        var runtime = runtime();
        assertThatThrownBy(() -> runtime.invoke("key", "deferred.probe", project, run, Map.of()))
                .hasMessage("DEFERRED_REQUIRES_ENFORCED_DECISION");
        assertThatThrownBy(() -> runtime.invokeApplicationScoped("key", "deferred.probe", run, Map.of()))
                .hasMessage("DEFERRED_REQUIRES_ENFORCED_DECISION");
        verifyNoInteractions(repository);
        verify(adapter, never()).invoke(any());
    }

    @Test void explicitPendingRegistrationIsDurableAndDuplicateDoesNotResubmit() {
        var runtime = runtime();
        when(repository.claim(any())).thenReturn(true, false);
        when(repository.matchesIdentity(any())).thenReturn(true);
        when(repository.markPending(any(), any(), eq(60L))).thenReturn(true);
        final UUID[] submitted = new UUID[1];
        when(adapter.invoke(any())).thenAnswer(call -> {
            CapabilityInvocation invocation = call.getArgument(0);
            submitted[0] = invocation.invocationId();
            return new CapabilityResult(submitted[0], "key", "deferred.probe", CapabilityResult.Status.PENDING,
                    Map.of(), List.of(), Map.of(), List.of());
        });
        var first = runtime.invokeDeferredAllowed("key", "deferred.probe", project, run, Map.of());
        when(repository.findByInvocationKey("key")).thenReturn(Optional.of(pending(submitted[0])));
        var replay = runtime.invokeDeferredAllowed("key", "deferred.probe", project, run, Map.of());
        assertThat(first.status()).isEqualTo(CapabilityResult.Status.PENDING);
        assertThat(replay.invocationId()).isEqualTo(first.invocationId());
        assertThat(replay.status()).isEqualTo(CapabilityResult.Status.PENDING);
        verify(adapter, times(1)).invoke(any());
        verify(repository, never()).complete(any(), any());
    }

    @Test void completionRequiresIdentityAndCurrentAdapterAuthorizationBeforeOneCas() {
        var runtime = runtime();
        UUID id = UUID.randomUUID();
        var record = pending(id);
        when(repository.findByInvocationKey("key")).thenReturn(Optional.of(record));
        when(repository.completePending(id, terminal(id))).thenReturn(true, false);
        assertThat(runtime.completeDeferred(terminal(id))).isTrue();
        assertThat(runtime.completeDeferred(terminal(id))).isFalse();
        var order = inOrder(adapter, repository);
        order.verify(repository).findByInvocationKey("key");
        order.verify(adapter).requireAuthorizedCompletion(record);
        order.verify(repository).completePending(id, terminal(id));
        assertThatThrownBy(() -> runtime.completeDeferred(terminal(UUID.randomUUID())))
                .hasMessageContaining("identity mismatch");
        verify(repository, times(2)).completePending(any(), any());
    }

    @Test void revokedCompletionCannotWakeOrChangeAnyInvocation() {
        var runtime = runtime();
        UUID id = UUID.randomUUID();
        var record = pending(id);
        when(repository.findByInvocationKey("key")).thenReturn(Optional.of(record));
        doThrow(new IllegalStateException("revoked")).when(adapter).requireAuthorizedCompletion(record);
        assertThatThrownBy(() -> runtime.completeDeferred(terminal(id))).hasMessage("revoked");
        verify(repository, never()).completePending(any(), any());
    }

    @Test void crashWindowRunningIsStillInProgressAndNeverARegisteredDependency() {
        var runtime = runtime();
        UUID id = UUID.randomUUID();
        var record = new CapabilityInvocationRecord(id, "key", project, run, "deferred.probe", Map.of(),
                CapabilityResult.Status.RUNNING, null, Instant.now(), null);
        when(repository.claim(any())).thenReturn(false);
        when(repository.matchesIdentity(any())).thenReturn(true);
        when(repository.findByInvocationKey("key")).thenReturn(Optional.of(record));
        assertThat(runtime.invokeDeferredAllowed("key", "deferred.probe", project, run, Map.of()).status())
                .isEqualTo(CapabilityResult.Status.IN_PROGRESS);
        verify(adapter, never()).invoke(any());
        verify(repository, never()).markPending(any(), any(), anyLong());
    }
}
