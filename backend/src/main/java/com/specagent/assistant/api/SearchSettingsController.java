package com.specagent.assistant.api;

import com.specagent.assistant.config.SearchSettings;
import com.specagent.assistant.tool.TavilyWebService;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@com.specagent.common.LocalServiceSettingsApi
@RequestMapping("/api/v1/settings/search")
public class SearchSettingsController {
    private final SearchSettings settings;
    private final TavilyWebService tavily;
    public SearchSettingsController(SearchSettings settings,TavilyWebService tavily) { this.settings=settings; this.tavily=tavily; }
    public record Save(boolean enabled,String apiKey,boolean importEnvironment,Long revision) {
        @Override public String toString() { return "SearchSave[enabled="+enabled+"]"; }
    }
    public record Test(String apiKey) { @Override public String toString() { return "SearchTest[redacted]"; } }
    @GetMapping public Map<String,Object> get() { return settings.view(); }
    @PutMapping public Map<String,Object> save(@RequestBody Save body) { return settings.save(body.enabled(),body.apiKey(),body.importEnvironment(),body.revision()); }
    @DeleteMapping public Map<String,Object> clear(@RequestParam(required=false) Long revision) { return settings.clear(revision); }
    @PostMapping("/test") public Map<String,Object> test(@RequestBody Test body) {
        var snapshot=settings.snapshot(); String key=body.apiKey()==null?snapshot.key():SearchSettings.validateKey(body.apiKey());
        var result=tavily.testConnection(key==null?"":key);
        String code=result.errorCode()==null?"OK":result.errorCode();
        if(body.apiKey()==null) settings.recordTest(snapshot.revision(),code);
        return Map.of("success",code.equals("OK"),"code",code,"target",body.apiKey()==null?"SAVED":"DRAFT");
    }
}
