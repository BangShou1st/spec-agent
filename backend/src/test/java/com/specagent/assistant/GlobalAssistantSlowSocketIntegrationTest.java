package com.specagent.assistant;

import com.specagent.assistant.conversation.*;
import com.specagent.assistant.runtime.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

/** Real PostgreSQL + embedded Tomcat + a TCP peer advertising a tiny receive window.
 * This exercises actual servlet socket backpressure, not a mocked send/sleep. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GlobalAssistantSlowSocketIntegrationTest {
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired GlobalAssistantRunEventService events;
    @Autowired GlobalAssistantRunRepository runs;
    @Autowired GlobalAssistantStreamService streams;
    @Autowired GlobalAssistantStreamCatchUpPoller catchUpPoller;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;

    @Test void unreadSocketCannotHoldBusinessCommitOrHealthySubscriberOfSameRun() throws Exception {
        var thread=conversations.createThread();
        var run=conversations.createRun(thread.id(),"transport-test","test","test");
        var pool=Executors.newFixedThreadPool(2);
        // The test profile does not enable Spring scheduling. Run the real production
        // poller at its normal cadence, including its cursor-notification coalescing.
        var polling=Executors.newSingleThreadScheduledExecutor();
        polling.scheduleWithFixedDelay(catchUpPoller::catchUp,1,1,TimeUnit.SECONDS);
        try(var slow=new Socket();var http=HttpClient.newHttpClient()) {
            events.append(run.id(),"RUN_STARTED",Map.of());
            slow.setReceiveBufferSize(1024);
            slow.setSoTimeout(5000);
            slow.connect(new InetSocketAddress("127.0.0.1",port),5000);
            String path="/api/v1/global-assistant/runs/"+run.id()+"/events";
            slow.getOutputStream().write(("GET "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+port
                    +"\r\nAccept: text/event-stream\r\nConnection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            // Read only response headers, then deliberately leave this real socket unread.
            StringBuilder headers=new StringBuilder();
            while(!headers.toString().endsWith("\r\n\r\n")) {
                int next=slow.getInputStream().read();
                if(next<0 || headers.length()>16384)throw new AssertionError("Missing SSE response headers");
                headers.append((char)next);
            }
            assertThat(headers.toString()).startsWith("HTTP/1.1 200");
            var response=http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path))
                    .header("Accept","text/event-stream").GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            assertThat(response.statusCode()).isEqualTo(200);
            try(var input=response.body()) {
                var received=new java.util.concurrent.CopyOnWriteArrayList<Integer>();
                var healthy=pool.submit(()->{
                    var reader=new BufferedReader(new InputStreamReader(input,StandardCharsets.UTF_8));
                    String line;
                    while((line=reader.readLine())!=null) {
                        if(line.startsWith("data:")) {
                            var envelope=new com.fasterxml.jackson.databind.ObjectMapper().readTree(line.substring(5).trim());
                            received.add(envelope.path("sequence").asInt());
                            if("RUN_COMPLETED".equals(envelope.path("type").asText()))return List.copyOf(received);
                        }
                    }
                    return List.copyOf(received);
                });
                var producer=pool.submit(()->{
                    for(int i=0;i<120;i++) events.append(run.id(),"ANSWER_DELTA",Map.of("text","x".repeat(16_384)));
                });
                producer.get(10,TimeUnit.SECONDS);
                boolean socketWriteObserved=false;
                long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
                while(System.nanoTime()<deadline && !socketWriteObserved) {
                    socketWriteObserved=Thread.getAllStackTraces().entrySet().stream()
                            .filter(entry->entry.getKey().getName().equals("ga-sse-delivery"))
                            .anyMatch(entry->java.util.Arrays.stream(entry.getValue()).anyMatch(frame->
                                    frame.getClassName().contains("NioEndpoint$NioSocketWrapper") && frame.getMethodName().equals("doWrite")));
                    if(!socketWriteObserved)Thread.sleep(20);
                }
                assertThat(socketWriteObserved).as("a production delivery thread must actually encounter Tomcat socket backpressure").isTrue();
                assertThat(runs.findById(run.id()).orElseThrow().status()).isEqualTo(GlobalAssistantRunStatus.CREATED);
                runs.terminalize(run.id(),GlobalAssistantRunStatus.COMPLETED,null);
                events.append(run.id(),"RUN_COMPLETED",Map.of());
                try {
                    assertThat(healthy.get(10,TimeUnit.SECONDS)).containsExactlyElementsOf(
                            java.util.stream.IntStream.rangeClosed(1,122).boxed().toList());
                } catch(java.util.concurrent.TimeoutException failure) {
                    System.out.println("Healthy subscriber received sequences="+received.size()+"; active subscribers="+streams.subscriberCount());
                    Thread.getAllStackTraces().entrySet().stream().filter(e->e.getKey().getName().equals("ga-sse-delivery"))
                            .forEach(e->System.out.println(e.getKey()+" "+java.util.Arrays.toString(e.getValue())));
                    throw failure;
                }
            }
        } finally {
            polling.shutdownNow();
            pool.shutdownNow();
            jdbc.update("DELETE FROM global_assistant_run_events WHERE run_id=?",run.id());
            jdbc.update("DELETE FROM global_assistant_runs WHERE id=?",run.id());
            jdbc.update("DELETE FROM global_assistant_threads WHERE id=?",thread.id());
        }
    }
}
