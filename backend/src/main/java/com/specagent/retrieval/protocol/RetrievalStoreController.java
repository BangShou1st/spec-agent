package com.specagent.retrieval.protocol;

import com.specagent.common.BrainConnectionSettings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static com.specagent.retrieval.protocol.RetrievalWire.*;

@RestController
@RequestMapping("/internal/v1/retrieval-store")
public class RetrievalStoreController {
    private final BrainConnectionSettings properties;
    private final RetrievalStore store;
    private final RetrievalIndexJobs jobs;
    private final RetrievalSourceJobs sourceJobs;
    private final HelpCorpusJobs helpJobs;
    public RetrievalStoreController(BrainConnectionSettings properties,RetrievalStore store,RetrievalIndexJobs jobs,RetrievalSourceJobs sourceJobs,HelpCorpusJobs helpJobs) {
        this.properties=properties; this.store=store; this.jobs=jobs; this.sourceJobs=sourceJobs; this.helpJobs=helpJobs;
    }
    private boolean authorized(String token) {
        String secret=properties.getInternalSecret();
        return secret!=null && !secret.isBlank() && token!=null && MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8));
    }
    @GetMapping("/health") public ResponseEntity<?> health(@RequestHeader(value="X-Spec-Agent-Internal-Token",required=false) String token) {
        if(!authorized(token)) return ResponseEntity.status(401).build();
        boolean ready;
        try { ready=store.storageReady(); } catch(RuntimeException unavailable) { ready=false; }
        return ResponseEntity.ok(Map.of("protocolVersion","retrieval.v1","storeReady",ready,"profileId",PROFILE));
    }
    @PostMapping("/projection-grants") public ResponseEntity<?> projectionGrants(@RequestHeader(value="X-Spec-Agent-Internal-Token",required=false) String token,@RequestBody String body) {
        if(!authorized(token)) return ResponseEntity.status(401).build();
        UUID requestId=new UUID(0,0);
        try { var request=read(body,SplitRequest.class); requestId=request.requestId(); return ResponseEntity.ok("HELP_CHUNK".equals(request.sourceKind())?helpJobs.validateGrant(request):sourceJobs.validateGrant(request)); }
        catch(IllegalStateException ex) {
            String code=Set.of("UNSUPPORTED_PROFILE","DEADLINE_EXCEEDED","STALE_SCOPE_GRANT","STALE_WORKLOAD","INDEX_GENERATION_MISMATCH","SOURCE_VERSION_MISMATCH").contains(ex.getMessage())?ex.getMessage():"RETRIEVAL_UNAVAILABLE";
            return ResponseEntity.status(409).body(Map.of("protocolVersion","retrieval.v1","requestId",requestId,"errorCode",code));
        } catch(IllegalArgumentException ex) { return ResponseEntity.badRequest().body(Map.of("errorCode","RETRIEVAL_PROTOCOL_ERROR")); }
    }
    @PostMapping("/index-grants") public ResponseEntity<?> indexGrant(@RequestHeader(value="X-Spec-Agent-Internal-Token",required=false) String token,@RequestBody String body) {
        if(!authorized(token)) return ResponseEntity.status(401).build();
        UUID requestId=new UUID(0,0);
        try { var request=read(body,IndexBatch.class); requestId=request.requestId(); return ResponseEntity.ok(jobs.validateGrant(request)); }
        catch(IllegalStateException ex) {
            String code=Set.of("STALE_SCOPE_GRANT","STALE_WORKLOAD","SOURCE_VERSION_MISMATCH","INDEX_GENERATION_MISMATCH","UNSUPPORTED_PROFILE","DEADLINE_EXCEEDED").contains(ex.getMessage())?ex.getMessage():"RETRIEVAL_UNAVAILABLE";
            return ResponseEntity.status(409).body(Map.of("protocolVersion","retrieval.v1","requestId",requestId,"errorCode",code));
        } catch(IllegalArgumentException ex) { return ResponseEntity.badRequest().body(Map.of("errorCode","RETRIEVAL_PROTOCOL_ERROR")); }
    }
    @PostMapping("/candidates") public ResponseEntity<?> candidates(@RequestHeader(value="X-Spec-Agent-Internal-Token",required=false) String token,@RequestBody String body) {
        return invoke(token,body,true);
    }
    @PostMapping("/validate-sources") public ResponseEntity<?> validateSources(@RequestHeader(value="X-Spec-Agent-Internal-Token",required=false) String token,@RequestBody String body) {
        return invoke(token,body,false);
    }
    private ResponseEntity<?> invoke(String token,String body,boolean candidates) {
        String secret=properties.getInternalSecret();
        if(secret==null || secret.isBlank() || token==null || !MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8),token.getBytes(StandardCharsets.UTF_8)))
            return ResponseEntity.status(401).build();
        UUID requestId=null;
        try {
            Envelope request=candidates?read(body,Candidates.class):read(body,Validation.class); requestId=request.requestId();
            return ResponseEntity.ok(candidates?store.candidates((Candidates)request):store.validateSources((Validation)request));
        } catch(IllegalStateException ex) {
            String code=Set.of("UNSUPPORTED_PROFILE","DEADLINE_EXCEEDED","STALE_SCOPE_GRANT","STALE_WORKLOAD","INDEX_GENERATION_MISMATCH").contains(ex.getMessage())?ex.getMessage():"RETRIEVAL_UNAVAILABLE";
            return ResponseEntity.status(409).body(Map.of("protocolVersion","retrieval.v1","requestId",requestId==null?new UUID(0,0):requestId,"errorCode",code));
        } catch(IllegalArgumentException ex) { return ResponseEntity.badRequest().body(Map.of("errorCode","RETRIEVAL_PROTOCOL_ERROR")); }
    }
}
