package com.specagent.retrieval.config;

import com.specagent.common.BrainConnectionSettings;
import com.specagent.retrieval.protocol.*;
import static com.specagent.retrieval.protocol.RetrievalWire.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Authenticated, workload-bound embeddings. Caller cannot choose provider URL/key/model or unrelated texts. */
@RestController
@RequestMapping("/internal/v1/retrieval-store")
public class EmbeddingBrokerController {
    private final BrainConnectionSettings auth;
    private final RetrievalStore store;
    private final RetrievalIndexJobs jobs;
    private final EmbeddingSettings settings;
    private final EmbeddingTransport transport;
    public EmbeddingBrokerController(BrainConnectionSettings auth,RetrievalStore store,RetrievalIndexJobs jobs,EmbeddingSettings settings,EmbeddingTransport transport) {
        this.auth=auth; this.store=store; this.jobs=jobs; this.settings=settings; this.transport=transport;
    }
    private Envelope request(String body) {
        try {
            var node=JSON.readTree(body);
            return node.has("sources")?read(body,IndexBatch.class):read(body,Search.class);
        } catch(java.io.IOException ex) { throw new IllegalArgumentException("RETRIEVAL_PROTOCOL_ERROR"); }
    }
    private boolean authorized(String token) {
        String key=auth.getInternalSecret();
        return key!=null && !key.isBlank() && token!=null && MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8));
    }
    @PostMapping("/embedding-profile") public ResponseEntity<?> profile(@RequestHeader(value="X-Spec-Agent-Internal-Token",required=false) String token,@RequestBody String body) {
        return invoke(token,body,false);
    }
    @GetMapping("/active-embedding") public ResponseEntity<?> active(@RequestHeader(value="X-Spec-Agent-Internal-Token",required=false) String token) {
        if(!authorized(token)) return ResponseEntity.status(401).build();
        var id=store.activeHelpProfile();
        if(PROFILE.equals(id)) return ResponseEntity.ok(Map.of("legacy",true));
        var p=settings.profile(id); boolean available=true;
        if(p.config().provider().equals("OPENAI_COMPATIBLE")) {
            try { var key=settings.key(p); available=key!=null&&!key.isBlank() || EmbeddingSettings.localApi(p.config()); }
            catch(RuntimeException ex) { available=false; }
        }
        return ResponseEntity.ok(Map.of("legacy",false,"profileId",p.profileId(),"semantic",p.semantic(),"config",p.config(),"serviceRevision",p.serviceRevision(),"available",available));
    }
    @PostMapping("/embeddings") public ResponseEntity<?> embeddings(@RequestHeader(value="X-Spec-Agent-Internal-Token",required=false) String token,@RequestBody String body) {
        return invoke(token,body,true);
    }
    private ResponseEntity<?> invoke(String token,String body,boolean compute) {
        if(!authorized(token)) return ResponseEntity.status(401).build();
        UUID id=new UUID(0,0);
        try {
            var request=request(body); id=request.requestId(); var scope=store.guard(request);
            if(request instanceof IndexBatch batch) jobs.validateGrant(batch);
            if(request instanceof Search search && !search.query().equals(scope.query())) throw new IllegalStateException("STALE_SCOPE_GRANT");
            var profile=settings.profile(request.profileId());
            if(!compute) return ResponseEntity.ok(Map.of("profileId",profile.profileId(),"semantic",profile.semantic(),"config",profile.config(),"serviceRevision",profile.serviceRevision()));
            if(!profile.config().provider().equals("OPENAI_COMPATIBLE")) throw new IllegalStateException("UNSUPPORTED_PROFILE");
            List<String> texts=request instanceof IndexBatch batch?batch.sources().stream().map(IndexSource::text).toList():List.of(((Search)request).query());
            if(request instanceof Search && "qwen-instruct.v1".equals(profile.config().queryStrategy()))
                texts=List.of("Instruct: "+EmbeddingSettings.QUERY_INSTRUCTION+"\nQuery: "+texts.getFirst());
            var vectors=new ArrayList<double[]>();
            final String credential=settings.key(profile);
            long expires=System.nanoTime()+java.time.Duration.between(java.time.Instant.now(),request.deadline()).toNanos();
            for(int n=0;n<texts.size();n+=profile.config().batchSize()) {
                long left=java.util.concurrent.TimeUnit.NANOSECONDS.toSeconds(expires-System.nanoTime());
                if(left<1) throw new IllegalStateException("DEADLINE_EXCEEDED");
                var c=profile.config();
                var bounded=new EmbeddingSettings.Config(c.provider(),c.baseUrl(),c.model(),(int)Math.min(c.timeoutSeconds(),left),c.batchSize(),c.queryStrategy());
                vectors.addAll(transport.remote(bounded,credential,texts.subList(n,Math.min(texts.size(),n+c.batchSize()))));
            }
            if(vectors.stream().anyMatch(v->v.length!=profile.dimensions())) throw new IllegalStateException("EMBEDDING_DIMENSION_MISMATCH");
            store.guard(request); // Revocation, deadline and source changes fence results after the network call.
            settings.key(profile); // Explicit credential revocation also fences in-flight broker results.
            if(request instanceof IndexBatch batch) jobs.validateGrant(batch);
            return ResponseEntity.ok(Map.of("profileId",profile.profileId(),"requestId",request.requestId(),"dimensions",profile.dimensions(),"vectors",vectors));
        } catch(RuntimeException ex) {
            return ResponseEntity.status(409).body(Map.of("protocolVersion","retrieval.v2","requestId",id,"errorCode",ServiceSettingsErrors.safeCode(ex)));
        }
    }
}
