package com.specagent.assistant.tool;

import com.specagent.capability.*;
import java.util.*;
import java.util.function.*;
import org.springframework.stereotype.Component;

/** Standard host capability, exposed to create_agent through the existing registry. */
@Component
public class WebSearchCapability implements PreparedCapabilityAdapter {
    protected final TavilyWebService web;
    public WebSearchCapability(TavilyWebService web) { this.web = web; }
    public CapabilityDescriptor descriptor() {
        return new CapabilityDescriptor("web.search","1",
                "Search current public web information with Tavily on demand. Send only necessary query text, never internal project history or secrets. Results are EXTERNAL_EVIDENCE search snippets, not full pages or confirmed project facts. Cite returned web:sourceId references. No retries; at most 5 results.",
                Map.of("query",Map.of("type","string","required",true,"minLength",1,"maxLength",2000),
                        "limit",Map.of("type","integer","required",false,"minimum",1,"maximum",5),
                        "topic",Map.of("type","string","required",false,"enum",List.of("general","news")),
                        "timeRange",Map.of("type","string","required",false,"enum",List.of("day","week","month","year"))),
                Map.of("sources",Map.of("type","array")),true,SideEffectClass.NONE,List.of(),List.of(GlobalAssistantToolCatalog.SUPPORT_MARKER));
    }
    public CapabilityResult invoke(CapabilityInvocation invocation) {
        return GlobalAssistantToolFailures.failed(invocation,descriptor().capabilityId(),"WEB_PREPARATION_REQUIRED","Use the GA preparation boundary");
    }
    public Function<CapabilityInvocation,CapabilityResult> prepare(Map<String,Object> args,BooleanSupplier active) {
        var observation = web.prepare(descriptor().capabilityId(),args,active);
        return invocation -> {
            if (!active.getAsBoolean()) throw new IllegalStateException("GA_EXECUTION_FENCE");
            if (observation.errorCode() != null) return GlobalAssistantToolFailures.failed(invocation,descriptor().capabilityId(),observation.errorCode(),"Web request failed: " + observation.errorCode());
            return new CapabilityResult(invocation.invocationId(),invocation.invocationKey(),descriptor().capabilityId(),CapabilityResult.Status.SUCCEEDED,
                    Map.of("sources",observation.sources()),observation.sources().stream().map(s -> "web:" + s.get("sourceId")).toList(),
                    Map.of("kind","EXTERNAL_EVIDENCE","provider","TAVILY"),observation.warnings());
        };
    }
}
