package com.specagent.assistant.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import com.specagent.assistant.runtime.*;
import com.specagent.assistant.tool.*;
import com.specagent.assistant.conversation.GlobalAssistantEventType;
import com.specagent.capability.*;
import com.specagent.common.Hashes;
import com.specagent.common.Maps;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Service;

/** Authenticated native capability boundary; the existing registry/runtime remains the execution owner. */
@Service
public class GaCapabilityBroker {
    private record Request(String protocolVersion, UUID runId, long executionEpoch, UUID leaseId,
                           String toolCallId, String capabilityId, String descriptorVersion, String catalogHash,
                           Map<String,Object> arguments, String argumentsHash) {}
    public record Result(int status, Object body) {}
    private final ObjectMapper mapper=new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
            .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    private final GaExecutionStore executions;
    private final GaCatalogProjection catalogs;
    private final CapabilityRuntime runtime;
    private final GlobalAssistantRunEventService events;
    private final GlobalAssistantUiActionValidator navigation;
    public GaCapabilityBroker(GaExecutionStore executions, GaCatalogProjection catalogs, CapabilityRuntime runtime,
            GlobalAssistantRunEventService events, GlobalAssistantUiActionValidator navigation) {
        this.executions=executions; this.catalogs=catalogs; this.runtime=runtime; this.events=events; this.navigation=navigation;
    }
    public Result invoke(String body) {
        try { return new Result(200,mapper.readTree(complete(read(body)))); }
        catch (IllegalArgumentException ex) { return error(400,"GA_CAPABILITY_PROTOCOL_ERROR"); }
        catch (IllegalStateException ex) {
            String code=Set.of("GA_EXECUTION_FENCE","GA_MODEL_CATALOG_MISMATCH","GA_TOOL_NOT_RECORDED",
                    "GA_CALL_ID_CONFLICT","GA_CALL_IN_PROGRESS_OR_UNKNOWN","GA_BUDGET_EXHAUSTED",
                    "GA_CALL_NOT_RESERVED","GA_CAPABILITY_REVOKED").contains(ex.getMessage()) ? ex.getMessage() : "GA_HOST_STATE_ERROR";
            return error(409,code);
        } catch (Exception ex) { return error(503,"GA_HOST_STATE_ERROR"); }
    }
    private String complete(Request request) {
        var scope=new GaExecutionStore.Scope(request.runId(),request.executionEpoch(),request.leaseId());
        var frozen=executions.catalog(scope);
        if (!frozen.hash().equals(request.catalogHash())) throw new IllegalStateException("GA_MODEL_CATALOG_MISMATCH");
        var descriptor=frozen.descriptors().stream().filter(d -> d.capabilityId().equals(request.capabilityId())
                && d.version().equals(request.descriptorVersion())).findFirst()
                .orElseThrow(() -> new IllegalStateException("GA_CAPABILITY_REVOKED"));
        // Current host policy/catalog can revoke a formerly frozen capability but can never expand it.
        if (!catalogs.current().descriptors().contains(descriptor)) throw new IllegalStateException("GA_CAPABILITY_REVOKED");
        String args=catalogs.canonical(request.arguments());
        if (!executions.recordedTool(scope,request.toolCallId(),descriptor.name(),args))
            throw new IllegalStateException("GA_TOOL_NOT_RECORDED");
        UUID call=UUID.nameUUIDFromBytes((request.runId()+":"+request.toolCallId()).getBytes(StandardCharsets.UTF_8));
        var reservation=executions.reserve(scope,call,"TOOL",Hashes.sha256Hex(catalogs.canonical(request)));
        if (!reservation.fresh()) {
            if ("SUCCEEDED".equals(reservation.status())) return reservation.result();
            throw new IllegalStateException("GA_CALL_IN_PROGRESS_OR_UNKNOWN");
        }
        try {
            if (executions.uncertainTool(scope,call,descriptor.name(),args))
                throw new IllegalStateException("GA_CALL_IN_PROGRESS_OR_UNKNOWN");
            executions.beginReservedTool(scope,call,() -> events.append(request.runId(),GlobalAssistantEventType.TOOL_STARTED,
                    Map.of("capabilityId",request.capabilityId(),"toolCallId",request.toolCallId(),"arguments",request.arguments())));
            var prepared = executions.repeatedToolWithoutNewObservation(scope,descriptor.name(),args) ? null
                    : runtime.prepareApplicationScoped(request.capabilityId(),request.arguments(),() -> executions.active(scope),scope.runId());
            return executions.performReservedTool(scope,call,() -> execute(request,scope,descriptor,prepared));
        } catch (RuntimeException ex) {
            executions.unknown(scope,call); throw ex;
        }
    }
    private String execute(Request request,GaExecutionStore.Scope scope,GaCatalogProjection.Descriptor descriptor, CapabilityRuntime.PreparedApplicationCall prepared) {
        // Fence and policy are rechecked under the run lock immediately before the side effect.
        if (!catalogs.current().descriptors().contains(descriptor)) throw new IllegalStateException("GA_CAPABILITY_REVOKED");
        if (executions.repeatedToolWithoutNewObservation(scope,descriptor.name(),catalogs.canonical(request.arguments())))
            return result(request,"FAILED",Map.of(),List.of(),"REPEATED_TOOL_CALL");
        if (request.capabilityId().equals("user-input.request")) {
            Object question=request.arguments().get("question");
            if (!request.arguments().keySet().equals(Set.of("question")) || !(question instanceof String text)
                    || text.isBlank() || text.length()>4000) return result(request,"FAILED",Map.of(),List.of(),"TOOL_ARGUMENT_INVALID");
            events.append(request.runId(),GlobalAssistantEventType.TOOL_COMPLETED,Map.of("capabilityId",request.capabilityId(),
                    "toolCallId",request.toolCallId(),"summary","已请求补充信息"));
            return result(request,"USER_INPUT_REQUIRED",Map.of("question",question),List.of(),null);
        }
        if (request.capabilityId().equals("ui.navigate")) return navigate(request);
        // Runtime's durable invocation key is semantic, so another tool-call ID cannot repeat a local mutation.
        String key="ga-native:"+request.runId()+":"+request.capabilityId()+":"
                +(descriptor.readOnly() ? Hashes.sha256Hex(request.toolCallId()) : request.argumentsHash());
        CapabilityResult result=prepared == null
                ? runtime.invokeApplicationScoped(key,request.capabilityId(),request.runId(),request.arguments())
                : runtime.invokePreparedApplicationScoped(key,request.capabilityId(),request.runId(),request.arguments(),prepared);
        if (result.status()==CapabilityResult.Status.IN_PROGRESS || result.status()==CapabilityResult.Status.RUNNING)
            throw new IllegalStateException("GA_CALL_IN_PROGRESS_OR_UNKNOWN");
        boolean success=result.status()==CapabilityResult.Status.SUCCEEDED || result.status()==CapabilityResult.Status.REPLAYED;
        if (success) {
            var projects=GlobalAssistantToolPresentation.projectResources(request.capabilityId(),result.content());
            var sources=GlobalAssistantToolPresentation.retrievalResources(request.capabilityId(),result.content());
            var refs=new ArrayList<Map<String,Object>>(projects); refs.addAll(sources);
            var payload=new LinkedHashMap<String,Object>();
            payload.putAll(Map.of("capabilityId",request.capabilityId(),"toolCallId",request.toolCallId(),"resourceRefs",refs,"resultCount",request.capabilityId().equals("help.search")?sources.size():projects.size()));
            String kind=GlobalAssistantToolPresentation.resultKind(request.capabilityId());
            if (kind!=null) payload.put("resultKind",kind);
            events.append(request.runId(),GlobalAssistantEventType.TOOL_COMPLETED,payload);
        }
        String failure="TOOL_EXECUTION_FAILED";
        if (result.content().get("errorCode") instanceof String code && code.matches("[A-Z_]{1,128}")) failure=code;
        var sourceRefs=new LinkedHashSet<>(result.sourceRefs());
        if(success) for(var resource:GlobalAssistantToolPresentation.projectResources(request.capabilityId(),result.content()))
            sourceRefs.add("project:"+resource.get("id")); // Host projection, never model-supplied references.
        return result(request,success ? "SUCCEEDED" : "FAILED",result.content(),List.copyOf(sourceRefs),success ? null : failure);
    }
    private String navigate(Request request) {
        var args=request.arguments(); Object destination=args.get("destination"), resource=args.get("resourceId");
        if (!Set.of("destination","resourceId").containsAll(args.keySet()) || !(destination instanceof String)
                || !Set.of("PROJECTS","PROJECT","SKILLS").contains(destination))
            return result(request,"FAILED",Map.of(),List.of(),"TOOL_ARGUMENT_INVALID");
        Map<String,Object> content=new LinkedHashMap<>(); content.put("destination",destination);
        List<String> refs=List.of();
        if ("PROJECT".equals(destination)) {
            if (!(resource instanceof String id)) return result(request,"FAILED",Map.of(),List.of(),"TOOL_ARGUMENT_INVALID");
            UUID project;
            try { project=navigation.requireExistingProject(id); }
            catch (com.specagent.assistant.model.GlobalAssistantModelException ex) {
                return result(request,"FAILED",Map.of(),List.of(),"PROJECT_NOT_FOUND".equals(ex.errorCode())
                        ? "PROJECT_NOT_FOUND" : "TOOL_ARGUMENT_INVALID");
            }
            content.put("resourceId",project.toString()); refs=List.of("project:"+project);
        } else if (resource!=null) return result(request,"FAILED",Map.of(),List.of(),"TOOL_ARGUMENT_INVALID");
        events.append(request.runId(),GlobalAssistantEventType.UI_ACTION,content);
        events.append(request.runId(),GlobalAssistantEventType.TOOL_COMPLETED,Map.of("capabilityId",request.capabilityId(),
                "toolCallId",request.toolCallId(),"summary","已发送导航指令"));
        return result(request,"SUCCEEDED",content,refs,null);
    }
    private String result(Request request,String status,Map<String,Object> content,List<String> refs,String error) {
        String json=catalogs.canonical(Maps.of("protocolVersion","ga-capability-result.v1","toolCallId",request.toolCallId(),
                "status",status,"content",content,"sourceRefs",refs,"errorCode",error));
        if (json.getBytes(StandardCharsets.UTF_8).length>262144 || refs.size()>64)
            throw new IllegalStateException("GA_HOST_RESULT_LIMIT");
        if("FAILED".equals(status)) events.append(request.runId(),GlobalAssistantEventType.TOOL_FAILED,
            Map.of("capabilityId",request.capabilityId(),"toolCallId",request.toolCallId(),"errorCode",error==null?"TOOL_EXECUTION_FAILED":error));
        return json;
    }
    private Request read(String body) {
        try {
            if (body==null || body.getBytes(StandardCharsets.UTF_8).length>262144) throw new IllegalArgumentException();
            Request request=mapper.readValue(body,Request.class);
            if (!"ga-capability-invocation.v1".equals(request.protocolVersion()) || request.runId()==null || request.leaseId()==null
                    || request.executionEpoch()<1 || request.toolCallId()==null || request.toolCallId().isBlank() || request.toolCallId().length()>128
                    || request.capabilityId()==null || request.capabilityId().length()>128 || request.descriptorVersion()==null
                    || request.descriptorVersion().isBlank() || request.descriptorVersion().length()>64
                    || request.catalogHash()==null || !request.catalogHash().matches("[0-9a-f]{64}") || request.arguments()==null
                    || !Hashes.sha256Hex(catalogs.canonical(request.arguments())).equals(request.argumentsHash()))
                throw new IllegalArgumentException();
            return request;
        } catch (Exception ex) { throw new IllegalArgumentException("Invalid GA capability request"); }
    }
    private Result error(int status,String code) { return new Result(status,Map.of("errorCode",code)); }
}
