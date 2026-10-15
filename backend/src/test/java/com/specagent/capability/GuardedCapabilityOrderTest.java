package com.specagent.capability;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GuardedCapabilityOrderTest {
    private final CapabilityRegistry registry=mock(CapabilityRegistry.class);
    private final CapabilityInvocationRepository repository=mock(CapabilityInvocationRepository.class);
    private final GuardedCapabilityAdapter adapter=mock(GuardedCapabilityAdapter.class);
    private final CapabilityRuntime runtime=new CapabilityRuntime(registry,repository);

    @Test void authorizationPrecedesFirstClaimAndAdapterExecution() {
        when(registry.findAdapter("guarded")).thenReturn(Optional.of(adapter));
        when(repository.claim(any())).thenReturn(true);
        when(adapter.invoke(any())).thenAnswer(call -> {
            CapabilityInvocation i=call.getArgument(0);
            return new CapabilityResult(i.invocationId(),i.invocationKey(),i.capabilityId(),
                    CapabilityResult.Status.SUCCEEDED,Map.of(),List.of(),Map.of(),List.of());
        });
        runtime.invoke("key","guarded",UUID.randomUUID(),UUID.randomUUID(),Map.of());
        var order=inOrder(adapter,repository);
        order.verify(adapter).requireAuthorizedInvocation(any());
        order.verify(repository).claim(any());
        order.verify(adapter).invoke(any());
        order.verify(repository).complete(any(),any());
    }

    @Test void authorizationPrecedesReadingRecordedPrivateResult() {
        UUID project=UUID.randomUUID(),run=UUID.randomUUID();
        when(registry.findAdapter("guarded")).thenReturn(Optional.of(adapter));
        when(repository.claim(any())).thenReturn(false);
        when(repository.matchesIdentity(any())).thenReturn(true);
        when(repository.findByInvocationKey("key")).thenReturn(Optional.of(new CapabilityInvocationRecord(
                UUID.randomUUID(),"key",project,run,"guarded",Map.of(),CapabilityResult.Status.SUCCEEDED,
                Map.of("content",Map.of("private","body")),Instant.EPOCH,Instant.EPOCH)));
        assertThat(runtime.invoke("key","guarded",project,run,Map.of()).status()).isEqualTo(CapabilityResult.Status.REPLAYED);
        var order=inOrder(adapter,repository);
        order.verify(adapter).requireAuthorizedInvocation(any());
        order.verify(repository).claim(any());
        order.verify(repository).matchesIdentity(any());
        order.verify(repository).findByInvocationKey("key");
        verify(adapter,never()).invoke(any());
    }

    @Test void authorizationFailureNeitherClaimsNorReadsCachedPayload() {
        when(registry.findAdapter("guarded")).thenReturn(Optional.of(adapter));
        doThrow(new IllegalStateException("refused")).when(adapter).requireAuthorizedInvocation(any());
        assertThatThrownBy(()->runtime.invoke("key","guarded",UUID.randomUUID(),UUID.randomUUID(),Map.of()))
                .isInstanceOf(IllegalStateException.class).hasMessage("refused");
        verifyNoInteractions(repository);
    }
}
