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
    private final com.specagent.model.provider.OpenCodeModelCatalog modelProtocols;
    public GaExecutionCoordinator(GaExecutionPreparation preparation,GaExecutionCompletion completion,
            GaExecutionStore executions,GaBrainSettings properties,
            com.specagent.model.provider.OpenCodeModelCatalog modelProtocols) {
        this.preparation=preparation; this.completion=completion; this.executions=executions; this.properties=properties;
        this.modelProtocols=modelProtocols;
    }
    public void executeRun(UUID thread,UUID run,String text,GaHostContext.UiRequest ui) {
        GaExecutionPreparation.Prepared prepared=null;
        GaExecutionCompletion.StreamState state=null;
        try {
            // 协议门禁依赖可信模型元数据。这里(执行准备事务与 Run 锁之外)先预热
            // 元数据缓存,使 GaExecutionPreparation 内的门禁能纯内存判定——绝不在
            // 持有 Run 锁的准备工作事务内引入网络请求。刷新按 TTL 单飞与节流,
            // 失败不抛出(视图标记为不可用,交由门禁失败关闭)。
            modelProtocols.warmMetadata();
            prepared=preparation.prepare(thread,run,text,ui);
            if(prepared==null) return;
            state=completion.newStream(run);
            completion.accept(prepared,call(prepared,state),state);
        } catch(GlobalAssistantRunClaimedException ex) {
            // A duplicate dispatcher cannot fail or replace the owning execution.
        } catch(ProtocolMismatchException ex) {
            // 协议版本不兼容是部署错误(design §8):两端版本不同步,如实暴露
            // 类型化错误码,绝不默认放行,也绝不伪装成不可用。
            log.warn("GA protocol mismatch run={} detail={}", run, ex.getMessage());
            completion.failed(run,prepared==null?null:prepared.model(),state,
                    com.specagent.assistant.GlobalAssistantErrorCode.GA_PROTOCOL_MISMATCH);
        } catch(Exception ex) {
            log.warn("GA coordination failed run={} category={}",run,ex.getClass().getSimpleName());
            boolean unavailable=false;
            for(Throwable cause=ex;cause!=null;cause=cause.getCause())
                if(cause instanceof java.net.ConnectException || cause instanceof HttpConnectTimeoutException) unavailable=true;
            completion.failed(run,prepared==null?null:prepared.model(),state,unavailable?"GA_PYTHON_UNAVAILABLE":"GA_EXECUTION_FAILED");
        }
    }

    /**
     * 执行信封与执行事件流各自需要的协议版本(design §8)。Python /health
     * 以 {@code gaExecutionProtocolVersions} 公告本构建支持的范围;两者取
     * 交集为空时必须在发起执行前失败——旧请求绝不默认放行。
     */
    private static final List<String> REQUIRED_PROTOCOL_VERSIONS =
            List.of("ga-execution.v1", "ga-execution-event.v1");

    /** 协议版本协商失败的类型化信号;区别于网络不可达。 */
    static final class ProtocolMismatchException extends IOException {
        ProtocolMismatchException(String message) { super(message); }
    }

    /**
     * 发起执行前的协议协商:读取 Brain /health 的版本公告并核对必需版本。
     * 健康端点读不到按不可达处理(由调用方的既有分类收敛);公告里缺任何
     * 一个必需版本即拒绝执行。
     */
    private void negotiateProtocol(HttpClient client, URI base) throws IOException, InterruptedException {
        HttpRequest request=HttpRequest.newBuilder(base.resolve("/health"))
                .timeout(Duration.ofSeconds(3)).GET().build();
        HttpResponse<String> response=client.send(request,HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200)
            throw new IOException("Brain health status " + response.statusCode());
        var versions=new com.fasterxml.jackson.databind.ObjectMapper().readTree(response.body())
                .path("gaExecutionProtocolVersions");
        if(!versions.isArray())
            throw new ProtocolMismatchException(
                    "Brain /health does not advertise gaExecutionProtocolVersions; "
                            + "the Python service predates the execution protocol negotiation");
        for(String required : REQUIRED_PROTOCOL_VERSIONS) {
            boolean found=false;
            for(var advertised : versions) {
                if(required.equals(advertised.asText())) { found=true; break; }
            }
            if(!found)
                throw new ProtocolMismatchException(
                        "Brain does not support required protocol version " + required
                                + " (advertised: " + versions + ")");
        }
    }
    private List<String> call(GaExecutionPreparation.Prepared prepared,GaExecutionCompletion.StreamState state) throws Exception {
        URI base=URI.create(properties.getBaseUrl());
        if(!Set.of("http","https").contains(base.getScheme()) || base.getUserInfo()!=null || base.getQuery()!=null || base.getFragment()!=null)
            throw new IllegalArgumentException("Configured Brain origin required");
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofMillis(properties.getConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        // 协议协商先于执行信封的发出:版本不兼容时绝不让一个本端无法解读的
        // 请求进入 Brain 的执行路径(design §8"不默认允许旧请求")。
        negotiateProtocol(client, base);
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
