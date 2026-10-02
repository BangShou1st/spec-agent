package com.specagent.assistant.api;

import com.specagent.assistant.tool.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/global-assistant")
public class GaToolsController {
    private final TavilyWebService web;
    private final GaRetrievalReadiness retrieval;
    private final GaCatalogProjection catalog;
    public GaToolsController(TavilyWebService web, GaRetrievalReadiness retrieval, GaCatalogProjection catalog) {
        this.web=web; this.retrieval=retrieval; this.catalog=catalog;
    }
    @GetMapping("/tools")
    public Map<String,Object> tools() {
        return Map.of("engineVersion","langchain-ga.v1","webConfigured",web.configured(),"retrievalReady",retrieval.ready(),"retrievalStatus",retrieval.status(),
                "capabilities",catalog.current().descriptors().stream().map(GaCatalogProjection.Descriptor::capabilityId).toList());
    }
}
