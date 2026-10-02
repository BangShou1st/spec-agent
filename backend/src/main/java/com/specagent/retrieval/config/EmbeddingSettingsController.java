package com.specagent.retrieval.config;

import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@com.specagent.common.LocalServiceSettingsApi
@RequestMapping("/api/v1/settings/retrieval")
public class EmbeddingSettingsController {
    private final EmbeddingSettings settings;
    private final EmbeddingTransport transport;
    private final EmbeddingRebuilds rebuilds;
    public EmbeddingSettingsController(EmbeddingSettings settings,EmbeddingTransport transport,EmbeddingRebuilds rebuilds) {
        this.settings=settings; this.transport=transport; this.rebuilds=rebuilds;
    }
    @GetMapping public Map<String,Object> get() { return Map.of("service",settings.view(),"index",rebuilds.view()); }
    @PutMapping public Map<String,Object> save(@RequestBody EmbeddingSettings.Draft draft) { settings.save(draft); return get(); }
    @DeleteMapping("/credential") public Map<String,Object> clear(@RequestParam long revision) { settings.clearCredential(revision); return get(); }
    @PostMapping("/test") public Map<String,Object> test(@RequestBody EmbeddingSettings.Draft draft) {
        var config=settings.validate(draft.config()); String key=settings.draftKey(new EmbeddingSettings.Draft(config,draft.apiKey(),draft.revision()));
        long revision=settings.revision(); boolean saved=draft.apiKey()==null && config.equals(settings.current()) && draft.revision()!=null && draft.revision()==revision;
        try {
            if(config.provider().equals("OLLAMA") && key!=null) throw new IllegalArgumentException("OLLAMA_GATEWAY_AUTH_UNSUPPORTED");
            var result=transport.probe(config,key); UUID testId=settings.probe(config,key,result.dimensions(),result.digest());
            if(saved) settings.register(config,result.dimensions(),result.digest(),revision);
            return Map.of("success",true,"code","OK","dimensions",result.dimensions(),"testId",testId,"target",saved?"SAVED":"DRAFT");
        } catch(RuntimeException ex) {
            String code=ServiceSettingsErrors.safeCode(ex); if(saved) settings.testFailure(revision,code);
            return Map.of("success",false,"code",code,"target",saved?"SAVED":"DRAFT");
        }
    }
    public record Rebuild(UUID corpusId,String profileId) {}
    @PostMapping("/rebuild") public Map<String,Object> rebuild(@RequestBody Rebuild request) { rebuilds.start(request.corpusId(),request.profileId()); return get(); }
    @PostMapping("/rebuild/{id}/retry") public Map<String,Object> retry(@PathVariable UUID id) { rebuilds.retry(id); return get(); }
    @PostMapping("/rebuild/{id}/activate") public Map<String,Object> activate(@PathVariable UUID id) { rebuilds.activate(id); return get(); }
    @PostMapping("/rebuild/{id}/discard") public Map<String,Object> discard(@PathVariable UUID id) { rebuilds.discard(id); return get(); }
}
