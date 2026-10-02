package com.specagent.retrieval.config;

import com.specagent.common.BrainConnectionSettings;
import com.specagent.retrieval.protocol.PythonRetrievalClient.LimitedBodySubscriber;
import com.fasterxml.jackson.databind.JsonNode;
import static com.specagent.retrieval.protocol.RetrievalWire.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Provider protocol lives in Java for remote credentials; Ollama computations live in Python. */
@Service
public class EmbeddingTransport {
    public record Probe(int dimensions,String digest) {}
    private final BrainConnectionSettings connection;
    private final String gaOrigin;
    private final Semaphore capacity=new Semaphore(1);
    public EmbeddingTransport(BrainConnectionSettings connection,@Value("${spec.global-assistant.brain-base-url:http://127.0.0.1:8101}") String origin) {
        this.connection=connection; this.gaOrigin=origin;
    }
    public Probe probe(EmbeddingSettings.Config c,String key) {
        if(c.provider().equals("OLLAMA")) {
            var node=post(gaOrigin+"/internal/v1/retrieval/ollama-probe",write(c),connection.getInternalSecret(),true,c.timeoutSeconds());
            int dim=node.path("dimensions").asInt(); if(dim<1 || dim>4096) throw new IllegalStateException("UNSUPPORTED_DIMENSIONS");
            if(!node.path("digest").asText().matches("[a-f0-9]{64}")) throw new IllegalStateException("EMBEDDING_INVALID_RESPONSE");
            return new Probe(dim,node.path("digest").asText());
        }
        // Different inputs test array ordering/cardinality. This endpoint is always /embeddings, never chat.
        var vectors=new ArrayList<double[]>();
        if(c.batchSize()==1) {
            vectors.addAll(remote(c,key,List.of("Spec Agent connection test document")));
            vectors.addAll(remote(c,key,List.of("Spec Agent connection test query")));
            if(vectors.get(0).length!=vectors.get(1).length) throw new IllegalStateException("EMBEDDING_DIMENSION_MISMATCH");
        } else vectors.addAll(remote(c,key,List.of("Spec Agent connection test document","Spec Agent connection test query")));
        return new Probe(vectors.getFirst().length,null);
    }
    public List<double[]> remote(EmbeddingSettings.Config c,String key,List<String> texts) {
        if(!c.provider().equals("OPENAI_COMPATIBLE") || texts.isEmpty() || texts.size()>c.batchSize()
                || texts.stream().anyMatch(t->t==null || t.isBlank() || t.length()>12000)
                || texts.stream().mapToInt(String::length).sum()>48000) throw new IllegalArgumentException("EMBEDDING_BATCH_LIMIT");
        if((key==null || key.isBlank()) && !EmbeddingSettings.localApi(c)) throw new IllegalStateException("EMBEDDING_NOT_CONFIGURED");
        var node=post(c.baseUrl()+"/embeddings",write(Map.of("model",c.model(),"input",texts,"encoding_format","float")),key,false,c.timeoutSeconds());
        var data=node.path("data"); if(!data.isArray() || data.size()!=texts.size()) throw new IllegalStateException("EMBEDDING_INVALID_RESPONSE");
        double[][] ordered=new double[texts.size()][]; int dimensions=0;
        for(var row:data) {
            var index=row.get("index"); var vector=row.path("embedding");
            if(index==null || !index.isIntegralNumber() || !index.canConvertToInt() || index.intValue()<0 || index.intValue()>=texts.size()
                    || ordered[index.intValue()]!=null || !vector.isArray() || vector.isEmpty() || vector.size()>4096)
                throw new IllegalStateException("EMBEDDING_INVALID_RESPONSE");
            if(dimensions!=0 && dimensions!=vector.size()) throw new IllegalStateException("EMBEDDING_DIMENSION_MISMATCH");
            dimensions=vector.size(); double[] values=new double[dimensions]; double squared=0;
            for(int n=0;n<dimensions;n++) {
                if(!vector.get(n).isNumber() || !Double.isFinite(vector.get(n).doubleValue())) throw new IllegalStateException("EMBEDDING_INVALID_RESPONSE");
                values[n]=vector.get(n).doubleValue(); squared+=values[n]*values[n];
            }
            if(!Double.isFinite(squared) || squared<=0) throw new IllegalStateException("EMBEDDING_INVALID_RESPONSE");
            ordered[index.intValue()]=values;
        }
        return Arrays.asList(ordered);
    }
    private JsonNode post(String url,String body,String key,boolean internal,int timeout) {
        boolean acquired=false;
        long expires=System.nanoTime()+TimeUnit.SECONDS.toNanos(timeout);
        try {
            acquired=capacity.tryAcquire(timeout,TimeUnit.SECONDS);
            if(!acquired) throw new IllegalStateException("EMBEDDING_BUSY");
            long remaining=expires-System.nanoTime();
            if(remaining<=0) throw new IllegalStateException("EMBEDDING_TIMEOUT");
            try(var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build()) {
                var builder=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofNanos(remaining)).header("Content-Type","application/json");
                if(key!=null && !key.isBlank()) builder.header(internal?"X-Spec-Agent-Internal-Token":"Authorization",internal?key:"Bearer "+key);
                var future=client.sendAsync(builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),info->new LimitedBodySubscriber(2*1024*1024));
                HttpResponse<byte[]> response;
                try { response=future.get(Math.max(1,expires-System.nanoTime()),TimeUnit.NANOSECONDS); } finally { if(!future.isDone()) future.cancel(true); }
                int status=response.statusCode();
                if(status!=200) {
                    if(internal) {
                        try {
                            String code=JSON.readTree(response.body()).path("detail").asText();
                            if(Set.of("EMBEDDING_MODEL_NOT_FOUND","EMBEDDING_CONNECTION_FAILED","EMBEDDING_TIMEOUT","EMBEDDING_INVALID_RESPONSE","UNSUPPORTED_DIMENSIONS").contains(code)) throw new IllegalStateException(code);
                        } catch(java.io.IOException ignored) { }
                    }
                    throw new IllegalStateException(status==401||status==403?"EMBEDDING_AUTHENTICATION_FAILED":status==429?"EMBEDDING_RATE_LIMITED":"EMBEDDING_PROVIDER_FAILED");
                }
                return JSON.readTree(response.body());
            }
        } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("EMBEDDING_INTERRUPTED"); }
        catch(TimeoutException ex) { throw new IllegalStateException("EMBEDDING_TIMEOUT"); }
        catch(IllegalStateException ex) { throw ex; }
        catch(Exception ex) {
            if(ex instanceof ExecutionException && ex.getCause() instanceof HttpTimeoutException) throw new IllegalStateException("EMBEDDING_TIMEOUT");
            throw new IllegalStateException("EMBEDDING_CONNECTION_FAILED");
        } finally { if(acquired) capacity.release(); }
    }
}
