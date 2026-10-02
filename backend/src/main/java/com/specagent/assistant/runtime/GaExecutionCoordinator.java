package com.specagent.assistant.runtime;

import com.specagent.assistant.config.GaBrainSettings;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/** Remote execution coordination only: one launch, no model/tool decision loop or fallback. */
@Service
public class GaExecutionCoordinator {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(GaExecutionCoordinator.class);
    private final GaExecutionPreparation preparation;
    private final GaExecutionCompletion completion;
    private final GaExecutionStore executions;
    private final GaBrainSettings properties;
    public GaExecutionCoordinator(GaExecutionPreparation preparation,GaExecutionCompletion completion,
            GaExecutionStore executions,GaBrainSettings properties) {
        this.preparation=preparation; this.completion=completion; this.executions=executions; this.properties=properties;
    }
    public void executeRun(UUID thread,UUID run,String text,GaHostContext.UiRequest ui) {
        GaExecutionPreparation.Prepared prepared=null;
        GaExecutionCompletion.StreamState state=null;
        try {
            prepared=preparation.prepare(thread,run,text,ui);
            if(prepared==null) return;
            state=completion.newStream(run);
            completion.accept(prepared,call(prepared,state),state);
        } catch(GlobalAssistantRunClaimedException ex) {
            // A duplicate dispatcher cannot fail or replace the owning execution.
        } catch(Exception ex) {
            log.warn("GA coordination failed run={} category={}",run,ex.getClass().getSimpleName());
            boolean unavailable=false;
            for(Throwable cause=ex;cause!=null;cause=cause.getCause())
                if(cause instanceof java.net.ConnectException || cause instanceof HttpConnectTimeoutException) unavailable=true;
            completion.failed(run,prepared==null?null:prepared.model(),state,unavailable?"GA_PYTHON_UNAVAILABLE":"GA_EXECUTION_FAILED");
        }
    }
    private List<String> call(GaExecutionPreparation.Prepared prepared,GaExecutionCompletion.StreamState state) throws Exception {
        URI base=URI.create(properties.getBaseUrl());
        if(!Set.of("http","https").contains(base.getScheme()) || base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null)
            throw new IllegalArgumentException("Configured Brain origin required");
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        var request=HttpRequest.newBuilder(base.resolve("/internal/v1/global-assistant/executions"))
                .timeout(Duration.ofSeconds(180)).header("X-Spec-Agent-Internal-Token",properties.getInternalSecret())
                .header("Content-Type","application/json").header("Accept","application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofString(prepared.body(),StandardCharsets.UTF_8)).build();
        AtomicReference<InputStream> input=new AtomicReference<>();
        ExecutorService executor=Executors.newSingleThreadExecutor(r -> { var t=new Thread(r,"ga-execution-http"); t.setDaemon(true); return t; });
        Future<List<String>> future=executor.submit(() -> {
            var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
            input.set(response.body());
            try(InputStream stream=response.body()) {
                if(response.statusCode()!=200) {
                    log.warn("GA execution HTTP rejected run={} status={}",prepared.scope().runId(),response.statusCode());
                    throw new IOException("Brain execution status");
                }
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                List<String> lines=new ArrayList<>();
                int total=0; boolean terminal=false;
                byte[] chunk=new byte[4096]; int count;
                while((count=stream.read(chunk))!=-1) {
                    total+=count;
                    if(total>2097152) throw new IOException("Execution event limit");
                    for(int index=0;index<count;index++) {
                        if(chunk[index]!='\n') {
                            bytes.write(chunk[index]);
                            if(bytes.size()>262144) throw new IOException("Execution frame limit");
                            continue;
                        }
                        if(terminal) throw new IOException("Post-terminal execution frame");
                        String line=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
                        bytes.reset(); lines.add(line);
                        if(lines.size()>8192) throw new IOException("Execution frame count");
                        var event=new com.fasterxml.jackson.databind.ObjectMapper().readTree(line);
                        terminal=Set.of("COMPLETED","USER_INPUT_REQUIRED","FAILED").contains(event.path("type").asText());
                        if(!terminal) completion.progress(prepared,line,state);
                    }
                }
                if(bytes.size()!=0 || !terminal) throw new IOException("Truncated execution event");
                return lines;
            }
        });
        boolean finished=false;
        try {
            while(true) {
                if(!executions.active(prepared.scope())) throw new IOException("Execution fenced");
                try { var lines=future.get(100,TimeUnit.MILLISECONDS); finished=true; return lines; }
                catch(TimeoutException ignored) { }
            }
        } finally {
            future.cancel(true);
            InputStream stream=input.get(); if(stream!=null) try { stream.close(); } catch(IOException ignored) { }
            executor.shutdownNow();
            if(!finished) {
                // Advisory only: persisted Java fences remain authoritative even if Python is gone.
                try {
                    client.send(HttpRequest.newBuilder(base.resolve("/internal/v1/global-assistant/executions/"+prepared.scope().runId()+"/cancel"))
                            .timeout(Duration.ofSeconds(1)).header("X-Spec-Agent-Internal-Token",properties.getInternalSecret())
                            .header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(
                                    "{\"executionEpoch\":"+prepared.scope().epoch()+",\"leaseId\":\""+prepared.scope().leaseId()+"\"}"))
                            .build(),HttpResponse.BodyHandlers.discarding());
                } catch(Exception ignored) { } // No retry, replacement executor or cancellation rollback.
            }
            client.shutdownNow();
        }
    }
}
