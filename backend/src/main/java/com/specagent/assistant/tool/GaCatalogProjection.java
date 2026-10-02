package com.specagent.assistant.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.specagent.common.Hashes;
import com.specagent.model.contract.GaModelContract;
import java.util.*;
import org.springframework.stereotype.Service;

/** Frozen model projection of the existing host catalog, plus two application interaction tools. */
@Service
public class GaCatalogProjection {
    public record Descriptor(String capabilityId, String version, String name, String description,
                             Map<String,Object> inputSchema, boolean readOnly, String sideEffectClass) {}
    public record Snapshot(String hash, List<Descriptor> descriptors, List<GaModelContract.Tool> tools) {}
    private final GlobalAssistantCatalogService catalog;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .enable(com.fasterxml.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY);
    public GaCatalogProjection(GlobalAssistantCatalogService catalog) { this.catalog=catalog; }
    public Snapshot current() {
        List<Descriptor> values = new ArrayList<>();
        for (var item : catalog.modelCatalog()) values.add(new Descriptor(item.capabilityId(),item.version(),
                name(item.capabilityId()),item.description(),item.inputSchema(),item.readOnly(),item.sideEffectClass().name()));
        values.add(new Descriptor("ui.navigate","1","ui_navigate","Navigate to a host-verified application page or project.",
                Map.of("destination",Map.of("type","string","required",true,"enum",List.of("PROJECTS","PROJECT","SKILLS")),
                        "resourceId",Map.of("type","string","required",false)),true,"NONE"));
        values.add(new Descriptor("user-input.request","1","user-input_request","Ask one necessary question and finish this run awaiting a new user turn.",
                Map.of("question",Map.of("type","string","required",true,"maxLength",4000)),true,"NONE"));
        return snapshot(values);
    }
    public Snapshot snapshot(List<Descriptor> descriptors) {
        if (descriptors.size()>12 || descriptors.stream().map(Descriptor::name).distinct().count()!=descriptors.size()
                || descriptors.stream().map(Descriptor::capabilityId).distinct().count()!=descriptors.size())
            throw new IllegalArgumentException("Invalid GA catalog projection");
        for (var descriptor : descriptors) {
            if (!name(descriptor.capabilityId()).equals(descriptor.name()) || descriptor.name().equals("bash") || descriptor.name().equals("read")
                    || !Set.of("NONE","LOCAL_DURABLE").contains(descriptor.sideEffectClass()))
                throw new IllegalArgumentException("Invalid GA catalog projection");
        }
        List<GaModelContract.Tool> tools=descriptors.stream().map(d -> new GaModelContract.Tool(d.name(),d.description(),schema(d.inputSchema()))).toList();
        return new Snapshot(Hashes.sha256Hex(canonical(descriptors)),List.copyOf(descriptors),tools);
    }
    public String canonical(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { throw new IllegalArgumentException("Invalid GA catalog JSON"); }
    }
    private static String name(String id) {
        String result=id.replace('.','_');
        if (!result.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid GA tool name");
        return result;
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> schema(Map<String,Object> simple) {
        Map<String,Object> result;
        if (!simple.containsKey("type")) {
            Map<String,Object> properties=new TreeMap<>(); List<String> required=new ArrayList<>();
            for (var entry : new TreeMap<>(simple).entrySet()) {
                if (!(entry.getValue() instanceof Map<?,?>)) throw new IllegalArgumentException("Invalid GA field schema");
                var child=new LinkedHashMap<>((Map<String,Object>)entry.getValue());
                Object needs=child.remove("required");
                if (needs!=null && !(needs instanceof Boolean)) throw new IllegalArgumentException("Invalid GA required field");
                if (Boolean.TRUE.equals(needs)) required.add(entry.getKey());
                properties.put(entry.getKey(),node(child));
            }
            result=Map.of("type","object","properties",properties,"required",required,"additionalProperties",false);
        } else result=node(simple);
        if (!"object".equals(result.get("type"))) throw new IllegalArgumentException("Invalid GA root schema");
        return result;
    }
    @SuppressWarnings("unchecked")
    private static Map<String,Object> node(Map<String,Object> source) {
        var result=new LinkedHashMap<>(source);
        if (!Set.of("type","description","enum","properties","required","additionalProperties","items",
                "minLength","maxLength","minimum","maximum","minItems","maxItems").containsAll(result.keySet()))
            throw new IllegalArgumentException("Unsupported GA schema keyword");
        Object kind=result.get("type");
        if (kind==null || !Set.of("object","array","string","integer","number","boolean").contains(kind))
            throw new IllegalArgumentException("Unsupported GA schema type");
        if (kind.equals("object")) {
            if (result.containsKey("additionalProperties") && !Boolean.FALSE.equals(result.get("additionalProperties")))
                throw new IllegalArgumentException("GA object schema must be closed");
            result.put("additionalProperties",false);
            Object children=result.getOrDefault("properties",Map.of());
            if (!(children instanceof Map<?,?>)) throw new IllegalArgumentException("Invalid GA properties");
            Map<String,Object> properties=new TreeMap<>();
            for (var entry : ((Map<String,Object>)children).entrySet()) {
                if (!(entry.getValue() instanceof Map<?,?>)) throw new IllegalArgumentException("Invalid GA property");
                properties.put(entry.getKey(),node((Map<String,Object>)entry.getValue()));
            }
            result.put("properties",properties);
        } else if (kind.equals("array")) {
            if (!(result.get("items") instanceof Map<?,?>)) throw new IllegalArgumentException("GA array item schema required");
            result.put("items",node((Map<String,Object>)result.get("items")));
        }
        return result;
    }
}
