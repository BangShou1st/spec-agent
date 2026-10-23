package com.specagent.retrieval;

import com.specagent.common.Hashes;
import com.specagent.retrieval.protocol.*;
import static com.specagent.retrieval.protocol.RetrievalWire.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Both Agents share this real final validation boundary; no database or provider. */
class RagD4SharedSourceValidationTest {
    @ParameterizedTest @ValueSource(booleans={true,false})
    void finalSourceRejectionIsTypedFailureRatherThanSuccessfulZeroHits(boolean help) {
        var store=mock(RetrievalStore.class); var python=mock(PythonRetrievalClient.class);
        var host=new SharedRetrievalHost(store,python,mock(RetrievalIndexJobs.class),mock(RetrievalSourceJobs.class),mock(HelpCorpusJobs.class));
        UUID project=UUID.randomUUID(),run=UUID.randomUUID(),entry=UUID.randomUUID();
        var request=new Search("retrieval.v1",UUID.randomUUID(),new Workload("GA_RUN",run,1),
            new Grant(UUID.randomUUID(),1,Instant.now().plusSeconds(90)),PROFILE,UUID.randomUUID(),Instant.now().plusSeconds(60),
            "python-rag.v1","synthetic","EXPLICIT_SEARCH",new Limits(8,8000,48));
        var source=new Source(entry,project,project,null,"node:"+entry,"v1",Hashes.sha256Hex("evidence"),"NODE","PROJECT","USER_AUTHORED",1,"evidence",new Location(null,null,null));
        when(store.issueHelp(anyString(),anyInt(),any())).thenReturn(request);
        when(store.issueProject(any(),any(),anyString(),anySet())).thenReturn(request);
        when(python.search(request)).thenReturn(new SearchResult(request.protocolVersion(),request.requestId(),request.workload(),request.scopeGrant(),
            request.profileId(),request.indexGeneration(),request.deadline(),request.retrievalEngineVersion(),"OK",false,List.of(new Ranked(source,List.of("project-lexical"),1)),List.of()));
        when(store.validateSources(any())).thenReturn(new ValidationResult(request.protocolVersion(),request.requestId(),request.workload(),request.scopeGrant(),
            request.profileId(),request.indexGeneration(),request.deadline(),List.of(),List.of(entry)));
        assertThatThrownBy(()-> {
            if(help) host.searchHelp("synthetic",8,run);
            else host.search(new RetrievalQuery(project,null,null,"EXPLICIT_SEARCH","synthetic",List.of(RetrievalScope.PROJECT),8,8000,Set.of(),Set.of()),run,"GA_RUN",Set.of());
        }).isInstanceOf(IllegalStateException.class).hasMessage("SOURCE_VERSION_MISMATCH");
    }
}
