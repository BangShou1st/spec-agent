package com.specagent.assistant.tool;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** One bounded Tavily request, prepared outside the run transaction. No retries or other search provider. */
@Service
public class TavilyWebService {
    public record Observation(List<Map<String,Object>> sources, List<String> warnings, String errorCode) {}
    private final String key;
    private com.specagent.assistant.config.SearchSettings settings;
    private final URI origin;
    private final ObjectMapper mapper = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    @org.springframework.beans.factory.annotation.Autowired
    public TavilyWebService(com.specagent.assistant.config.SearchSettings settings) {
        this("", URI.create("https://api.tavily.com/")); this.settings=settings;
    }
    public TavilyWebService(String key) { this(key,URI.create("https://api.tavily.com/")); }
    TavilyWebService(String key, URI origin) { this.key = key == null ? "" : key.trim(); this.origin = origin; }
    public boolean configured() { return settings==null?!key.isBlank():settings.snapshot().configured(); }
    public Observation testConnection(String candidateKey) {
        return new TavilyWebService(candidateKey,origin).prepare("web.search",Map.of("query","Spec Agent connection test","limit",1),()->true);
    }
    public Observation prepare(String capability, Map<String,Object> args, BooleanSupplier active) {
        // Immutable request snapshot: a concurrent settings edit never changes its Authorization header.
        var snapshot=settings==null?null:settings.snapshot();
        final String requestKey=snapshot==null?key:snapshot.key();
        if (snapshot==null?requestKey.isBlank():!snapshot.configured()) return failure("WEB_NOT_CONFIGURED");
        var input = new AtomicReference<InputStream>();
        var executor = Executors.newSingleThreadExecutor(r -> { var t = new Thread(r,"ga-tavily-http"); t.setDaemon(true); return t; });
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            Future<Observation> future = executor.submit(() -> {
                Map<String,Object> body = request(capability,args);
                var request = HttpRequest.newBuilder(origin.resolve(capability.equals("web.search") ? "search" : "extract"))
                        .timeout(Duration.ofSeconds(20)).header("Authorization","Bearer " + requestKey)
                        .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build();
                var response = client.send(request,HttpResponse.BodyHandlers.ofInputStream());
                input.set(response.body());
                try (var stream = response.body()) {
                    int status = response.statusCode();
                    if (status != 200) return failure(status == 401 || status == 403 ? "WEB_AUTHENTICATION_FAILED"
                            : Set.of(429,432,433).contains(status) ? "WEB_RATE_LIMITED" : "WEB_PROVIDER_FAILED");
                    byte[] bytes = stream.readNBytes(1024 * 1024 + 1);
                    if (bytes.length > 1024 * 1024) return failure("WEB_RESPONSE_TOO_LARGE");
                    String json = StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                            .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
                    try { return observation(capability,mapper.readTree(json)); }
                    catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException invalid) { return failure("WEB_INVALID_RESPONSE"); }
                }
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            try {
                while (active.getAsBoolean()) {
                    if (System.nanoTime() >= deadline) return failure("WEB_TIMEOUT");
                    try { return future.get(50,TimeUnit.MILLISECONDS); }
                    catch (TimeoutException ignored) { }
                }
                throw new IllegalStateException("GA_EXECUTION_FENCE");
            } finally {
                future.cancel(true);
                if (input.get() != null) try { input.get().close(); } catch(IOException ignored) { }
                client.shutdownNow();
            }
        } catch (ExecutionException ex) {
            return failure(ex.getCause() instanceof IllegalArgumentException ? ("WEB_INVALID_RESPONSE".equals(ex.getCause().getMessage()) ? "WEB_INVALID_RESPONSE" : "TOOL_ARGUMENT_INVALID")
                    : ex.getCause() instanceof HttpTimeoutException ? "WEB_TIMEOUT" : ex.getCause() instanceof java.nio.charset.CharacterCodingException ? "WEB_INVALID_RESPONSE" : "WEB_CONNECTION_FAILED");
        } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("GA_EXECUTION_FENCE"); }
        finally { executor.shutdownNow(); }
    }
    private Map<String,Object> request(String capability, Map<String,Object> args) {
        if (capability.equals("web.search")) {
            if (!Set.of("query","limit","topic","timeRange").containsAll(args.keySet())) throw new IllegalArgumentException();
            String query = text(args,"query",true,2000);
            Object maximum = args.getOrDefault("limit",5);
            if (!(maximum instanceof Integer || maximum instanceof Long) || ((Number)maximum).longValue() < 1 || ((Number)maximum).longValue() > 5) throw new IllegalArgumentException();
            String topic = text(args,"topic",false,10), range = text(args,"timeRange",false,10);
            if (topic != null && !Set.of("general","news").contains(topic)) throw new IllegalArgumentException();
            if (range != null && !Set.of("day","week","month","year").contains(range)) throw new IllegalArgumentException();
            var body = new LinkedHashMap<String,Object>(Map.of("query",query,"max_results",maximum,"topic",topic == null ? "general" : topic,
                    "search_depth","basic","include_answer",false,"include_raw_content",false,"include_images",false,"include_published_date",true));
            if (range != null) body.put("time_range",range);
            return body;
        }
        if (!capability.equals("web.fetch") || !Set.of("url","query").containsAll(args.keySet())) throw new IllegalArgumentException();
        String url = WebUrlPolicy.requirePublic(text(args,"url",true,2000));
        String query = text(args,"query",false,2000);
        var body = new LinkedHashMap<String,Object>(Map.of("urls",List.of(url),"extract_depth","basic","format","text","include_images",false,"timeout",15));
        if (query != null) body.put("query",query);
        return body;
    }
    private Observation observation(String capability, JsonNode node) {
        if (!node.isObject() || !node.path("results").isArray()) return failure("WEB_INVALID_RESPONSE");
        boolean fetch = capability.equals("web.fetch");
        JsonNode failed = node.path("failed_results");
        if (fetch && (!failed.isArray() || node.path("results").size() != 1 || !failed.isEmpty())) return failure("WEB_EXTRACT_FAILED");
        List<Map<String,Object>> sources = new ArrayList<>(); int remaining = fetch ? 12000 : 8000;
        for (var item : node.path("results")) {
            if (sources.size() == (fetch ? 1 : 5)) break;
            String url = WebUrlPolicy.requirePublic(requiredText(item,"url"));
            String content = requiredText(item,fetch ? "raw_content" : "content");
            if (fetch && content.isBlank()) return failure("WEB_EXTRACT_FAILED");
            String bounded = bounded(content,remaining); remaining -= bounded.length();
            var source = new LinkedHashMap<String,Object>();
            source.put("sourceId",UUID.randomUUID().toString()); source.put("url",url);
            source.put("title",bounded(item.path("title").asText(url),200)); source.put("content",bounded);
            source.put("fetchedAt",Instant.now().toString()); source.put("truncated",bounded.length()<content.length());
            source.put("contentStage",fetch ? "EXTRACTED_TEXT" : "SEARCH_SNIPPET"); source.put("authority","EXTERNAL_EVIDENCE");
            if (item.path("published_date").isTextual()) source.put("publishedAt",bounded(item.path("published_date").textValue(),100));
            if (item.path("score").isNumber() && Double.isFinite(item.path("score").doubleValue())) source.put("providerScore",item.path("score").doubleValue());
            sources.add(Map.copyOf(source));
        }
        return new Observation(List.copyOf(sources),List.of(),null);
    }
    private static String requiredText(JsonNode node,String field) {
        if (!node.path(field).isTextual()) throw new IllegalArgumentException("WEB_INVALID_RESPONSE");
        return node.path(field).textValue();
    }
    private static String text(Map<String,Object> args,String name,boolean required,int maximum) {
        if (!args.containsKey(name) && !required) return null;
        if (!(args.get(name) instanceof String text) || text.isBlank() || text.length()>maximum) throw new IllegalArgumentException();
        return text.trim();
    }
    static String bounded(String value,int maximum) {
        int end = Math.min(value.length(),maximum);
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end-1))) end--;
        return value.substring(0,end);
    }
    private static Observation failure(String code) { return new Observation(List.of(),List.of(),code); }
}
