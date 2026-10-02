package com.specagent.retrieval.protocol;

import com.specagent.common.BrainConnectionSettings;
import static com.specagent.retrieval.protocol.RetrievalWire.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import org.springframework.stereotype.Service;

/** Java retains credentials only for host authentication; Ollama runs exclusively in Python. */
@Service
public class PythonRetrievalClient {
    private final BrainConnectionSettings properties;
    public PythonRetrievalClient(BrainConnectionSettings properties) { this.properties=properties; }

    public SplitResult split(SplitRequest request) { return post("/source-chunks",request,SplitResult.class); }
    public SearchResult search(Search request) { return post("/search",request,SearchResult.class); }
    public IndexResult index(IndexBatch request) { return post("/index-batches",request,IndexResult.class); }
    static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        private final java.util.concurrent.CompletableFuture<byte[]> result=new java.util.concurrent.CompletableFuture<>();
        private java.util.concurrent.Flow.Subscription subscription;
        LimitedBodySubscriber(int limit) { this.limit=limit; }
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription value) {
            if(subscription!=null) { value.cancel(); return; }
            subscription=value; value.request(1);
        }
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
            if(result.isDone()) return;
            long incoming=buffers.stream().mapToLong(java.nio.ByteBuffer::remaining).sum();
            if(incoming>limit-bytes.size()) {
                subscription.cancel(); result.completeExceptionally(new IOException("RETRIEVAL_RESPONSE_TOO_LARGE")); return;
            }
            for(var buffer:buffers) { byte[] part=new byte[buffer.remaining()]; buffer.get(part); bytes.writeBytes(part); }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
    private <T extends Envelope> T post(String path,Envelope request,Class<T> type) {
        validate(request);
        if(properties.getInternalSecret()==null || properties.getInternalSecret().isBlank()) throw new IllegalStateException("RETRIEVAL_UNAVAILABLE");
        long millis=Duration.between(Instant.now(),request.deadline()).toMillis();
        if(millis<=0) throw new IllegalStateException("DEADLINE_EXCEEDED");
        try (HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(Math.min(millis,2000))).build()) {
            URI origin=URI.create(properties.getBaseUrl());
            if(!java.util.Set.of("http","https").contains(origin.getScheme()) || origin.getHost()==null || origin.getUserInfo()!=null
                    || origin.getQuery()!=null || origin.getFragment()!=null) throw new IllegalStateException("RETRIEVAL_UNAVAILABLE");
            URI endpoint=new URI(origin.getScheme(),null,origin.getHost(),origin.getPort(),"/internal/v1/retrieval"+path,null,null);
            var httpRequest=HttpRequest.newBuilder(endpoint).timeout(Duration.ofMillis(millis))
                    .header("Content-Type","application/json").header("X-Spec-Agent-Internal-Token",properties.getInternalSecret())
                    .POST(HttpRequest.BodyPublishers.ofString(write(request),StandardCharsets.UTF_8)).build();
            // Stop allocation before the limit, including bodies without Content-Length.
            var pending=client.sendAsync(httpRequest, info -> new LimitedBodySubscriber(2*1024*1024));
            HttpResponse<byte[]> response;
            try { response=pending.get(Math.max(1,Duration.between(Instant.now(),request.deadline()).toMillis()),
                    java.util.concurrent.TimeUnit.MILLISECONDS); }
            finally { if(!pending.isDone()) pending.cancel(true); }
            if(response.statusCode()!=200) {
                // Only whitelisted contract errors enter diagnostics, never raw response bodies or credentials.
                var failure=JSON.readTree(response.body());
                String code=failure.path("errorCode").asText();
                if(java.util.Set.of("UNSUPPORTED_PROFILE","STALE_SCOPE_GRANT","STALE_WORKLOAD","SOURCE_VERSION_MISMATCH","INDEX_GENERATION_MISMATCH","OLLAMA_UNAVAILABLE","RETRIEVAL_UNAVAILABLE","DEADLINE_EXCEEDED","INVALID_VECTOR").contains(code)
                        && request.requestId().toString().equals(failure.path("requestId").asText())) throw new IllegalStateException(code);
                throw new IOException("Retrieval HTTP status "+response.statusCode());
            }
            if(response.body().length>2*1024*1024 || !request.deadline().isAfter(Instant.now()))
                throw new IllegalStateException("RETRIEVAL_UNAVAILABLE");
            T result=read(new String(response.body(),StandardCharsets.UTF_8),type);
            if(!binding(request).equals(binding(result))) throw new IllegalStateException("RETRIEVAL_UNAVAILABLE");
            return result;
        } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("RETRIEVAL_UNAVAILABLE"); }
        catch(IllegalStateException ex) { throw ex; }
        catch(Exception ex) { throw new IllegalStateException("RETRIEVAL_UNAVAILABLE",ex); }
    }
}
