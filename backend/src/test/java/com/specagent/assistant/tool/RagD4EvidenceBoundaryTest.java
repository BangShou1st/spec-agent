package com.specagent.assistant.tool;

import com.specagent.access.AuthorizationGuard;
import com.specagent.capability.*;
import com.specagent.common.Hashes;
import com.specagent.retrieval.RetrievalQuery;
import com.specagent.retrieval.protocol.*;
import com.specagent.workspace.project.ProjectRepository;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import static com.specagent.retrieval.protocol.RetrievalWire.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Actual capability adapters; mocked authorized store/host only. No DB or model calls. */
class RagD4EvidenceBoundaryTest {
    SharedRetrievalHost host=mock(SharedRetrievalHost.class);
    RetrievalStore store=mock(RetrievalStore.class);
    NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);
    GaRetrievalAccess access=mock(GaRetrievalAccess.class);
    UUID run=UUID.randomUUID(), project=UUID.randomUUID(), entry=UUID.randomUUID();
    HelpSearchCapability help;
    ProjectContentDiscoverCapability discover;

    @BeforeEach void setup() {
        when(access.host()).thenReturn(host);
        help=new HelpSearchCapability(access,store);
        discover=new ProjectContentDiscoverCapability(access,store,jdbc,mock(AuthorizationGuard.class),mock(ProjectRepository.class));
        when(jdbc.queryForList(anyString(),anyMap())).thenReturn(List.of(Map.of("title","synthetic")));
        when(jdbc.queryForObject(anyString(),anyMap(),eq(Boolean.class))).thenReturn(true);
        when(store.validateSources(any())).thenAnswer(call -> {
            Validation v=call.getArgument(0);
            return new ValidationResult(v.protocolVersion(),v.requestId(),v.workload(),v.scopeGrant(),v.profileId(),v.indexGeneration(),v.deadline(),
                v.sources().stream().map(Source::entryId).toList(),List.of());
        });
    }
    SearchResult result(boolean vector,boolean empty,boolean isHelp) {
        Source source=new Source(entry,isHelp?CuratedHelpSources.CORPUS:project,isHelp?null:project,null,
            isHelp?"help:synthetic":"node:"+entry,"version-1",Hashes.sha256Hex("only supported text"),
            isHelp?"HELP_CHUNK":"NODE",isHelp?"HELP":"PROJECT","EXTERNAL_EVIDENCE",2,
            "only supported text",new Location(null,null,null));
        return new SearchResult("retrieval.v1",UUID.randomUUID(),new Workload("GA_RUN",run,1),
            new Grant(UUID.randomUUID(),1,Instant.now().plusSeconds(60)),PROFILE,UUID.randomUUID(),Instant.now().plusSeconds(30),
            "python-rag.v1","SUCCEEDED",vector,empty?List.of():List.of(new Ranked(source,List.of("project-lexical"),1)),List.of("LEXICAL_ONLY"));
    }
    CapabilityInvocation invocation(String capability) { return new CapabilityInvocation(UUID.randomUUID(),"d4",capability,project,run,Map.of()); }
    CapabilityResult search(boolean isHelp) {
        if(isHelp) return help.prepare(Map.of("query","synthetic"),()->true,run).apply(invocation("help.search"));
        return discover.prepare(Map.of("query","synthetic","projectId",project.toString()),()->true,run).apply(invocation("project.content.discover"));
    }
    void arrange(SearchResult value,boolean isHelp) {
        if(isHelp) when(host.searchHelp(anyString(),anyInt(),eq(run))).thenReturn(value);
        else when(host.search(any(RetrievalQuery.class),eq(run),eq("GA_RUN"),anySet())).thenReturn(value);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void zeroHitsRetainVectorUnavailability(boolean isHelp) {
        arrange(result(true,true,isHelp),isHelp);
        var actual=search(isHelp);
        assertThat(actual.status()).isEqualTo(CapabilityResult.Status.SUCCEEDED);
        assertThat(actual.sourceRefs()).isEmpty();
        assertThat(actual.content()).containsEntry("vectorUnavailable",true);
        assertThat(actual.warnings()).contains("LEXICAL_ONLY");
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void trueZeroHitsRemainSuccessful(boolean isHelp) {
        arrange(result(false,true,isHelp),isHelp);
        assertThat(search(isHelp).content()).containsEntry("vectorUnavailable",false);
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void rejectedStaleOrWithdrawnSourceCannotMasqueradeAsZeroHits(boolean isHelp) {
        var value=result(false,false,isHelp); arrange(value,isHelp);
        doReturn(new ValidationResult(value.protocolVersion(),value.requestId(),value.workload(),
            value.scopeGrant(),value.profileId(),value.indexGeneration(),value.deadline(),List.of(),List.of(entry))).when(store).validateSources(any());
        var actual=search(isHelp);
        assertThat(actual.status()).isEqualTo(CapabilityResult.Status.FAILED);
        assertThat(actual.content()).containsEntry("errorCode","SOURCE_VERSION_MISMATCH");
        assertThat(actual.sourceRefs()).isEmpty();
        assertThat(GlobalAssistantToolPresentation.retrievalResources(actual.capabilityId(),actual.content())).isEmpty();
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void onlyHostValidatedVersionedEvidenceCreatesCards(boolean isHelp) {
        arrange(result(false,false,isHelp),isHelp);
        var actual=search(isHelp);
        var cards=GlobalAssistantToolPresentation.retrievalResources(actual.capabilityId(),actual.content());
        assertThat(cards).hasSize(1);
        assertThat(((Map<?,?>)cards.getFirst().get("metadata")).get("sourceVersion")).isEqualTo("version-1");
        assertThat(actual.sourceRefs()).containsExactly(isHelp?"help:synthetic":"node:"+entry);
        verify(store).validateSources(any());
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void toolFailureNeverBecomesSuccessfulEmptyEvidence(boolean isHelp) {
        if(isHelp) when(host.searchHelp(anyString(),anyInt(),eq(run))).thenThrow(new IllegalStateException("RETRIEVAL_UNAVAILABLE"));
        else when(host.search(any(),eq(run),eq("GA_RUN"),anySet())).thenThrow(new IllegalStateException("RETRIEVAL_UNAVAILABLE"));
        var actual=search(isHelp);
        assertThat(actual.status()).isEqualTo(CapabilityResult.Status.FAILED);
        assertThat(actual.content()).containsEntry("errorCode","RETRIEVAL_UNAVAILABLE");
        assertThat(actual.sourceRefs()).isEmpty();
    }
}
