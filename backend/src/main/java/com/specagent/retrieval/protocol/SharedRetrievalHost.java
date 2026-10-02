package com.specagent.retrieval.protocol;

import static com.specagent.retrieval.protocol.RetrievalWire.*;
import com.specagent.retrieval.RetrievalQuery;
import java.util.*;
import org.springframework.stereotype.Service;

/** Final product boundary, used by both project projections and bounded application tools. */
@Service
public class SharedRetrievalHost {
    private final RetrievalStore store;
    private final PythonRetrievalClient python;
    private final RetrievalIndexJobs jobs;
    private final RetrievalSourceJobs sourceJobs;
    private final HelpCorpusJobs helpJobs;
    public SharedRetrievalHost(RetrievalStore store,PythonRetrievalClient python,RetrievalIndexJobs jobs,RetrievalSourceJobs sourceJobs,HelpCorpusJobs helpJobs) {
        this.store=store; this.python=python; this.jobs=jobs; this.sourceJobs=sourceJobs; this.helpJobs=helpJobs;
    }
    public SearchResult search(RetrievalQuery query,UUID workload,String kind,Set<String> excluded) {
        Search request=store.issueProject(query,workload,kind,excluded);
        return search(request);
    }
    public SearchResult searchHelp(String query,int limit,UUID workload) { return search(store.issueHelp(query,limit,workload)); }
    private SearchResult search(Search request) {
        SearchResult result=python.search(request);
        if(!"python-rag.v1".equals(result.retrievalEngineVersion()) || result.items()==null || result.items().size()>request.limits().maxItems()
                || result.items().stream().map(i->i.source().entryId()).distinct().count()!=result.items().size()
                || result.items().stream().mapToInt(i->i.source().content().length()).sum()>request.limits().maxChars()
                || !java.util.Set.of("OK","VECTOR_UNAVAILABLE").contains(result.status())
                || result.vectorUnavailable()!=result.status().equals("VECTOR_UNAVAILABLE")
                || !result.warnings().equals(result.vectorUnavailable()?List.of("VECTOR_UNAVAILABLE"):List.of()))
            throw new IllegalStateException("RETRIEVAL_UNAVAILABLE");
        for(var item:result.items()) if(item.lanes()==null || item.lanes().isEmpty() || !LANES.containsAll(item.lanes())
                || !Double.isFinite(item.rankScore()) || item.rankScore()<0) throw new IllegalStateException("RETRIEVAL_UNAVAILABLE");
        var checked=store.validateSources(new Validation(request.protocolVersion(),request.requestId(),request.workload(),request.scopeGrant(),
                request.profileId(),request.indexGeneration(),request.deadline(),result.items().stream().map(Ranked::source).toList()));
        var allowed=new HashSet<>(checked.allowedEntryIds());
        return new SearchResult(result.protocolVersion(),result.requestId(),result.workload(),result.scopeGrant(),result.profileId(),
                result.indexGeneration(),result.deadline(),result.retrievalEngineVersion(),result.status(),result.vectorUnavailable(),
                result.items().stream().filter(item->allowed.contains(item.source().entryId())).toList(),result.warnings());
    }
    public boolean splitOneHelp(UUID generation) {
        var claimed=helpJobs.claim(generation); if(claimed.isEmpty()) return false;
        var request=claimed.get();
        try { helpJobs.commit(python.split(request)); return true; }
        catch(RuntimeException ex) { jobs.failed(request.jobId(),request.leaseId()); throw ex; }
    }
    public boolean splitOneSource(UUID project,UUID generation) {
        var claimed=sourceJobs.claim(project,generation);
        if(claimed.isEmpty()) return false;
        var request=claimed.get();
        try { sourceJobs.commit(python.split(request)); return true; }
        catch(RuntimeException ex) { jobs.failed(request.jobId(),request.leaseId()); throw ex; }
    }
    public boolean indexOneBatch(UUID corpus,UUID generation) {
        var claimed=jobs.claim(corpus,generation);
        if(claimed.isEmpty()) return false;
        var request=claimed.get();
        try { jobs.commit(python.index(request)); return true; }
        catch(RuntimeException ex) { jobs.failed(request.jobId(),request.leaseId()); throw ex; }
    }
}
