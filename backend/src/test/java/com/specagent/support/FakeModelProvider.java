package com.specagent.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Reusable L2/L5 provider fixture. No Python, Graph or provider-specific code. */
public final class FakeModelProvider implements AutoCloseable {
    public final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    public final List<String> keys = new CopyOnWriteArrayList<>();
    public volatile int status = 200;
    public volatile boolean badCall;
    public volatile boolean malformed;
    public volatile boolean missingDone;
    private final HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    public FakeModelProvider() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1", exchange -> {
            byte[] body;
            if ("GET".equals(exchange.getRequestMethod())) {
                body=mapper.writeValueAsBytes(Map.of("data",List.of(Map.of("id","catalog-model"))));
            } else {
                JsonNode request=mapper.readTree(exchange.getRequestBody()); requests.add(request);
                String key=exchange.getRequestHeaders().getFirst("Authorization");keys.add(key==null?"":key);
                Map<String,Object> message;
                boolean tools="required".equals(request.path("tool_choice").asText());
                String callId=requests.size()==1?"fixture-call":"fixture-call-"+requests.size();
                if (tools) message=Map.of("role","assistant","tool_calls",List.of(Map.of(
                        "id",callId,"type","function","function",Map.of("name",badCall?"wrong_tool":"probe_echo",
                        "arguments","{\"text\":\"probe ok\"}"),
                        "extra_content",Map.of("google",Map.of("thought_signature","fixture-signature")))));
                else message=Map.of("role","assistant","content",request.has("response_format")
                        || request.has("text") ? "{\"kind\":\"FINAL\",\"assistantText\":\"probe ok\"}" : "fixture answer");
                Object response=exchange.getRequestURI().getPath().endsWith("/responses")
                        ? Map.of("status","completed","output_text","{\"kind\":\"FINAL\",\"assistantText\":\"probe ok\"}")
                        : Map.of("choices",List.of(Map.of("message",message,"finish_reason",tools?"tool_calls":"stop")),
                        "usage",Map.of("prompt_tokens",1,"completion_tokens",1));
                body=malformed ? "invalid".getBytes(java.nio.charset.StandardCharsets.UTF_8) : mapper.writeValueAsBytes(response);
                if (request.path("stream").asBoolean()) {
                    var delta=mapper.valueToTree(message).deepCopy();
                    if (tools) {
                        var call=(com.fasterxml.jackson.databind.node.ObjectNode)delta.path("tool_calls").get(0);
                        call.put("index",0);call.remove("extra_content");
                    }
                    String frames="data: "+mapper.writeValueAsString(Map.of("choices",List.of(Map.of("index",0,"delta",delta))))+"\n\n"
                            +"data: "+mapper.writeValueAsString(Map.of("choices",List.of(Map.of("index",0,"delta",Map.of(),"finish_reason",tools?"tool_calls":"stop"))))+"\n\n"
                            +"data: "+mapper.writeValueAsString(Map.of("choices",List.of(),"usage",Map.of("prompt_tokens",1,"completion_tokens",1)))+"\n\n"
                            +(missingDone?"":"data: [DONE]\n\n");
                    body=frames.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type","text/event-stream");
                }
            }
            if (!exchange.getResponseHeaders().containsKey("Content-Type"))
                exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,body.length);
            try(var out=exchange.getResponseBody()){out.write(body);}
        });
        server.start();
    }
    public String baseUrl(){ return "http://127.0.0.1:"+server.getAddress().getPort()+"/v1"; }
    @Override public void close(){server.stop(0);}
}
