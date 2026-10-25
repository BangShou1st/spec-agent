package com.specagent.retrieval.context;

import com.specagent.capability.*;
import com.specagent.retrieval.*;
import com.specagent.retrieval.persistence.RetrievalEntryRepository;
import com.specagent.retrieval.search.HybridRetriever;
import com.specagent.workspace.node.NodeRepository;
import com.specagent.workspace.route.RouteRepository;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real memory.search -> search service chain with a mocked retrieval boundary. */
class RagD4MemoryEvidenceTest {
    HybridRetriever retriever=mock(HybridRetriever.class);
    RetrievalSearchService search=new RetrievalSearchService(retriever,mock(RetrievalEntryRepository.class),mock(RouteRepository.class),mock(NodeRepository.class));
    MemorySearchCapability capability=new MemorySearchCapability(search);
    UUID project=UUID.randomUUID(), run=UUID.randomUUID();
    CapabilityResult invoke() {
        return capability.invoke(new CapabilityInvocation(UUID.randomUUID(),"d4","memory.search",project,run,Map.of("query","synthetic")));
    }
    @Test void zeroItemsRetainAvailabilityAndPreserveTheExistingWorkloadKind() {
        when(retriever.usesSharedPython()).thenReturn(true);
        when(retriever.retrieveOutcome(any(),any(),eq("CONTEXT_PROJECTION"),eq(Set.of())))
            .thenReturn(new HybridRetriever.Outcome(List.of(),Map.of("vectorUnavailable",true,"retrievalEngineVersion","python-rag.v1")));
        var actual=invoke();
        assertThat(actual.status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
        assertThat(actual.content()).containsEntry("vectorUnavailable",true).containsEntry("items",List.of());
        assertThat(actual.sourceRefs()).isEmpty();
        verify(retriever,never()).retrieve(any());
    }
    @Test void unavailableIsFailedAndNeverOrdinaryZeroHit() {
        when(retriever.usesSharedPython()).thenReturn(true);
        when(retriever.retrieveOutcome(any(),any(),any(),any())).thenThrow(new IllegalStateException("RETRIEVAL_UNAVAILABLE"));
        var actual=invoke();
        assertThat(actual.status()).isEqualTo(CapabilityResult.Status.FAILED);
        assertThat(actual.content()).containsEntry("errorCode","RETRIEVAL_UNAVAILABLE");
        assertThat(actual.sourceRefs()).isEmpty();
    }
    @Test void scopeVersionFailureIsNeverDowngradedToAvailability() {
        when(retriever.usesSharedPython()).thenReturn(true);
        when(retriever.retrieveOutcome(any(),any(),any(),any())).thenThrow(new IllegalStateException("STALE_SCOPE_GRANT"));
        assertThatThrownBy(this::invoke).isInstanceOf(IllegalStateException.class).hasMessage("STALE_SCOPE_GRANT");
    }
    @Test void explicitLegacyCompatibilityRetainsTheExistingPath() {
        when(retriever.retrieve(any())).thenReturn(List.of());
        assertThat(invoke().status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
        verify(retriever).retrieve(any());
        verify(retriever,never()).retrieveOutcome(any(),any(),any(),any());
    }
}
