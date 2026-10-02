package com.specagent.assistant.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

/** Local Tavily HTTP fixtures only; never represents live internet qualification. */
class TavilyWebServiceTest {
    HttpServer server;
    ExecutorService executor;
    TavilyWebService web;
    ObjectMapper mapper=new ObjectMapper();
    AtomicInteger calls=new AtomicInteger();
    volatile String response="{\"results\":[]}";
    volatile int status=200;
    volatile long delay;
    volatile Map<String,Object> request;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor=Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            assertEquals("Bearer fixture-secret",exchange.getRequestHeaders().getFirst("Authorization"));
            request=mapper.readValue(exchange.getRequestBody(),Map.class);
            try { Thread.sleep(delay); } catch(InterruptedException stopped) { Thread.currentThread().interrupt(); }
            byte[] bytes=response.getBytes(StandardCharsets.UTF_8);
            try { exchange.sendResponseHeaders(status,bytes.length); exchange.getResponseBody().write(bytes); }
            finally { exchange.close(); }
        });
        server.start(); web=new TavilyWebService("fixture-secret",URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"));
    }
    @AfterEach void stop() { server.stop(0); executor.shutdownNow(); }
    @Test void chineseSearchUsesBoundedOfficialParametersAndSnippets() {
        response="{\"results\":[{\"url\":\"https://example.com/news\",\"title\":\"中文来源\",\"content\":\"搜索摘要\",\"score\":0.8,\"published_date\":\"2026-10-02\"}]}";
        var result=web.prepare("web.search",Map.of("query","最新中文资讯","limit",3,"topic","news","timeRange","week"),()->true);
        assertNull(result.errorCode()); assertEquals(1,result.sources().size());
        assertEquals("最新中文资讯",request.get("query")); assertEquals(3,request.get("max_results"));
        assertEquals(false,request.get("include_answer")); assertEquals(false,request.get("include_raw_content"));
        assertEquals("basic",request.get("search_depth")); assertEquals("week",request.get("time_range"));
        var source=result.sources().getFirst(); assertEquals("SEARCH_SNIPPET",source.get("contentStage"));
        assertEquals("EXTERNAL_EVIDENCE",source.get("authority")); UUID.fromString((String)source.get("sourceId"));
        assertEquals(1,calls.get()); assertFalse(source.toString().contains("fixture-secret"));
    }
    @Test void extractsOnePageWithQueryAndCharacterBudget() throws Exception {
        response=mapper.writeValueAsString(Map.of("results",List.of(Map.of("url","https://example.com/","raw_content","内容".repeat(7000))),"failed_results",List.of()));
        var result=web.prepare("web.fetch",Map.of("url","https://example.com/","query","相关段落"),()->true);
        assertNull(result.errorCode()); var source=result.sources().getFirst();
        assertEquals(12000,((String)source.get("content")).length()); assertEquals(true,source.get("truncated"));
        assertEquals("EXTRACTED_TEXT",source.get("contentStage")); assertEquals(List.of("https://example.com/"),request.get("urls"));
        assertEquals("text",request.get("format")); assertEquals("相关段落",request.get("query"));
    }
    @Test void searchEmptyIsSuccessButExtractFailedIsFailure() {
        assertNull(web.prepare("web.search",Map.of("query","没有结果"),()->true).errorCode());
        response="{\"results\":[],\"failed_results\":[{\"url\":\"https://example.com/\",\"error\":\"login wall\"}]}";
        assertEquals("WEB_EXTRACT_FAILED",web.prepare("web.fetch",Map.of("url","https://example.com/"),()->true).errorCode());
    }
    @Test void providerFailuresDoNotBecomeEmptySuccessOrRetry() {
        for(int code:new int[]{401,403,429,432,433,500}) {
            status=code;
            String expected=code==401||code==403?"WEB_AUTHENTICATION_FAILED":code==500?"WEB_PROVIDER_FAILED":"WEB_RATE_LIMITED";
            assertEquals(expected,web.prepare("web.search",Map.of("query","test"),()->true).errorCode());
        }
        assertEquals(6,calls.get());
    }
    @Test void invalidArgumentsAndMissingCredentialNeverSend() {
        for(var args:List.<Map<String,Object>>of(Map.of("query",""),Map.of("query","x","limit",6),Map.of("query","x","limit",1.5),Map.of("query","x","apiKey","secret"),Map.of("query","x","topic","finance")))
            assertEquals("TOOL_ARGUMENT_INVALID",web.prepare("web.search",args,()->true).errorCode());
        assertEquals("WEB_NOT_CONFIGURED",new TavilyWebService("").prepare("web.search",Map.of("query","x"),()->true).errorCode());
        assertEquals(0,calls.get());
    }
    @Test void unsafeTargetsAndProviderCanonicalUrlsAreRejected() {
        for(String url:List.of("file:///secret","data:text/plain,test","http://localhost/","http://127.0.0.1/","http://10.0.0.1/","http://192.168.1.1/","http://100.64.0.1/","http://[::1]/","http://[fc00::1]/","https://user:pass@example.com/"))
            assertEquals("TOOL_ARGUMENT_INVALID",web.prepare("web.fetch",Map.of("url",url),()->true).errorCode(),url);
        assertEquals(0,calls.get());
        response="{\"results\":[{\"url\":\"http://127.0.0.1/\",\"content\":\"bad\"}]}";
        assertEquals("WEB_INVALID_RESPONSE",web.prepare("web.search",Map.of("query","x"),()->true).errorCode());
    }
    @Test void malformedAndOversizedResponsesFailClosed() {
        for(String body:List.of("not JSON","{\"results\":[],\"results\":[]}","{\"results\":{}}","x".repeat(1024*1024+1))) {
            response=body; assertNotNull(web.prepare("web.search",Map.of("query","x"),()->true).errorCode());
        }
    }
    @Test void cancellationStopsNetworkWaitAndDoesNotCommitLateResult() throws Exception {
        delay=5000; AtomicBoolean active=new AtomicBoolean(true);
        var future=executor.submit(()->web.prepare("web.search",Map.of("query","x"),active::get));
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(2);
        while(calls.get()==0 && System.nanoTime()<until) Thread.sleep(10);
        long start=System.nanoTime(); active.set(false);
        var failure=assertThrows(ExecutionException.class,()->future.get(1,TimeUnit.SECONDS));
        assertInstanceOf(IllegalStateException.class,failure.getCause()); assertTrue(System.nanoTime()-start<TimeUnit.SECONDS.toNanos(1));
    }
}
