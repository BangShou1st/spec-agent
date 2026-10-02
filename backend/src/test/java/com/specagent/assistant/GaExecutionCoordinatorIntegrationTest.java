package com.specagent.assistant;

import com.specagent.assistant.config.GaBrainSettings;

import com.specagent.assistant.conversation.*;
import com.specagent.assistant.runtime.*;
import com.specagent.model.contract.GaModelContract;
import com.specagent.model.provider.OpenCodeZenTransport;
import com.specagent.modelsettings.*;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Product dispatch/lifecycle with real Python HTTP; provider is deterministic unless explicitly qualified. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GaExecutionCoordinatorIntegrationTest {
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired GlobalAssistantApplicationService application;
    @Autowired GlobalAssistantRunRepository runs;
    @Autowired GlobalAssistantRunEventService events;
    @Autowired ConversationDeleteService deletion;
    @Autowired GaExecutionCoordinator coordinator;
    @Autowired GaExecutionPreparation preparation;
    @Autowired GaExecutionStore executions;
    @Autowired GaExecutionCompletion completion;
    @Autowired com.specagent.workspace.project.ProjectDeletionService projectDeletion;
    @Autowired com.specagent.workspace.project.ProjectService projects;
    @Autowired GlobalAssistantRunLifecycleService lifecycle;
    @Autowired GaBrainSettings properties;
    @Autowired JdbcTemplate jdbc;
    @LocalServerPort int port;
    @MockBean OpenCodeSettingsRepository settings;
    @MockBean OpenCodeZenTransport transport;
    @MockBean com.specagent.skill.importing.GitSkillImporter gitImporter;
    @SpyBean com.specagent.assistant.tool.GaCatalogProjection catalogs;
    @SpyBean com.specagent.assistant.tool.TavilyWebService web;
    UUID thread;
    Process python;
    Path output;
    String oldBase;
    @BeforeEach void fixture() {
        thread=conversations.createThread().id();
        Instant revision=Instant.now();
        when(settings.find()).thenReturn(Optional.of(new OpenCodeSettings("test-key-no-network","work","test-model",revision,revision)));
        when(transport.completeNativeGa(any(),any(),any(),any(),any(),any())).thenReturn(reply("已完成"));
        doAnswer(invocation -> {
            var result=transport.completeNativeGa(invocation.getArgument(0),invocation.getArgument(1),invocation.getArgument(2),
                    invocation.getArgument(3),invocation.getArgument(4),invocation.getArgument(5));
            if(result!=null && result.toolCalls().isEmpty()) {
                com.specagent.model.contract.FragmentListener listener=invocation.getArgument(6);
                if(!listener.onFragment(result.content())) throw new com.specagent.model.contract.StreamCancelledException("Test consumer closed");
            }
            return result;
        }).when(transport).streamNativeGa(any(),any(),any(),any(),any(),any(),any());
        oldBase=properties.getBaseUrl();
    }
    @AfterEach void cleanup() throws Exception {
        stopPython();
        properties.setBaseUrl(oldBase);
        jdbc.update("UPDATE global_assistant_runs SET status='FAILED' WHERE thread_id=? AND status IN ('CREATED','RUNNING')",thread);
        if(conversations.findThread(thread).isPresent()) deletion.deleteThread(thread);
        for(UUID project:jdbc.queryForList("SELECT id FROM projects WHERE title=?",UUID.class,"ga-live-write-test-"+thread))
            projectDeletion.deleteProject(project);
        for(UUID staged:jdbc.queryForList("SELECT id FROM skill_staged_imports WHERE manifest LIKE ?",UUID.class,
                "%name: ga-native-live-"+thread+"%")) {
            jdbc.update("DELETE FROM skill_staged_files WHERE staged_import_id=?",staged);
            jdbc.update("DELETE FROM skill_staged_imports WHERE id=?",staged);
        }
        deleteTestLog();
    }
    @Test void defaultRunEngineAndLegacyHistoryRemainDistinct() {
        var old=create("历史消息");
        jdbc.update("UPDATE global_assistant_runs SET engine_version='java-legacy.v1',status='COMPLETED' WHERE id=?",old.id());
        conversations.appendAssistantMessage(thread,"历史回答",old.id());
        var current=create("新消息");
        assertEquals("langchain-ga.v1",jdbc.queryForObject("SELECT engine_version FROM global_assistant_runs WHERE id=?",String.class,current.id()));
        assertEquals("java-legacy.v1",jdbc.queryForObject("SELECT engine_version FROM global_assistant_runs WHERE id=?",String.class,old.id()));
        assertEquals("历史回答",conversations.listMessages(thread).get(1).content());
        assertThrows(GlobalAssistantRunClaimedException.class,()->preparation.prepare(thread,old.id(),"历史消息",null));
    }
    @Test void absentPythonFailsExplicitlyWithoutJavaFallback() throws Exception {
        int unused; try(var socket=new java.net.ServerSocket(0)) { unused=socket.getLocalPort(); }
        properties.setBaseUrl("http://127.0.0.1:"+unused);
        var run=application.createRun(thread,"无 Python 服务",null);
        awaitTerminal(run.id());
        assertEquals(GlobalAssistantRunStatus.FAILED,runs.findById(run.id()).orElseThrow().status());
        assertTrue(events.findByRun(run.id()).stream().anyMatch(e->e.type().equals("RUN_FAILED") && "GA_PYTHON_UNAVAILABLE".equals(e.payload().get("errorCode"))));
        verifyNoInteractions(transport);
    }
    @Test void durableExecutorClaimBindsEnvelopeAndCannotRestart() {
        var run=create("准备执行");
        var prepared=preparation.prepare(thread,run.id(),"准备执行",null);
        String claim=claim(prepared,"0".repeat(64));
        assertThrows(IllegalStateException.class,()->executions.claimExecutor(claim));
        String hash=jdbc.queryForObject("SELECT execution_request_hash FROM ga_executions WHERE run_id=?",String.class,run.id());
        assertNotNull(executions.claimExecutor(claim(prepared,hash)));
        assertThrows(IllegalStateException.class,()->executions.claimExecutor(claim(prepared,hash)));
        coordinator.executeRun(thread,run.id(),"准备执行",null);
        assertEquals(GlobalAssistantRunStatus.RUNNING,runs.findById(run.id()).orElseThrow().status());
        verifyNoInteractions(transport);
    }
    @Test void terminalEventsRejectTruncationForgeryAndWrongScopeBeforePublicWrites() throws Exception {
        var run=create("事件验证");
        var prepared=preparation.prepare(thread,run.id(),"事件验证",null);
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        String hash=jdbc.queryForObject("SELECT execution_request_hash FROM ga_executions WHERE run_id=?",String.class,run.id());
        executions.claimExecutor(claim(prepared,hash));
        UUID call=UUID.randomUUID();
        executions.reserve(prepared.scope(),call,"MODEL","a".repeat(64));
        executions.complete(prepared.scope(),call,mapper.writeValueAsString(reply("已验证的模型输出")));
        String status=mapper.writeValueAsString(Map.of("protocolVersion","ga-execution-event.v1","runId",run.id(),
                "executionEpoch",1,"eventId",UUID.randomUUID(),"sequence",1,"type","STATUS","payload",Map.of("stage","EXECUTING")));
        String forged=mapper.writeValueAsString(Map.of("protocolVersion","ga-execution-event.v1","runId",run.id(),
                "executionEpoch",1,"eventId",UUID.randomUUID(),"sequence",2,"type","COMPLETED","payload",Map.of("text","伪造输出")));
        assertThrows(IllegalArgumentException.class,()->completion.accept(prepared,List.of(status)));
        assertThrows(IllegalArgumentException.class,()->completion.accept(prepared,List.of(status.replace("\"sequence\":1","\"sequence\":4294967297"),forged)));
        assertThrows(IllegalArgumentException.class,()->completion.accept(prepared,List.of(status.replace("\"executionEpoch\":1","\"executionEpoch\":18446744073709551617"),forged)));
        assertThrows(IllegalArgumentException.class,()->completion.accept(prepared,List.of(status,forged)));
        assertThrows(IllegalArgumentException.class,()->completion.accept(prepared,List.of(status.replace(run.id().toString(),UUID.randomUUID().toString()),forged)));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ga_execution_events WHERE run_id=?",Integer.class,run.id()));
        assertEquals(1,conversations.listMessages(thread).size());
        assertEquals(GlobalAssistantRunStatus.RUNNING,runs.findById(run.id()).orElseThrow().status());
        assertEquals(1,events.findByRun(run.id()).size());
        lifecycle.interruptAndTerminalize(run.id());
        assertThrows(IllegalStateException.class,()->executions.claimExecutor(claim(prepared,hash)));
        assertFalse(executions.active(prepared.scope()));
    }
    @Test void actualProductDispatchUsesPythonAndDoesNotDuplicateTwoTurnHistory() throws Exception {
        startPython();
        conversations.appendUserMessage(thread,"旧会话请求",null);
        conversations.appendAssistantMessage(thread,"旧会话回答",null);
        var captured=new CopyOnWriteArrayList<GaModelContract.Request>();
        when(transport.completeNativeGa(any(),any(),any(),any(),any(),any())).thenAnswer(invocation -> {
            GaModelContract.Request request=invocation.getArgument(2); captured.add(request);
            if(captured.size()==1) return new GaModelContract.Response(GaModelContract.VERSION,"工具前说明不应成为正文",List.of(
                    new GaModelContract.ToolCall("coordinator-c1","project_list_recent",Map.of("limit",1))),"tool_calls",new GaModelContract.Usage(3,2));
            return reply(captured.size()==2?"已读取最近项目":"连续完成");
        });
        var first=application.createRun(thread,"第一轮请求",null);
        awaitTerminal(first.id());
        assertEquals(GlobalAssistantRunStatus.COMPLETED,runs.findById(first.id()).orElseThrow().status(),failure(first.id()));
        assertEquals(1,jdbc.queryForObject("SELECT tool_calls FROM ga_executions WHERE run_id=?",Integer.class,first.id()));
        var second=application.createRun(thread,"第二轮请求",null);
        awaitTerminal(second.id());
        assertEquals(GlobalAssistantRunStatus.COMPLETED,runs.findById(second.id()).orElseThrow().status(),failure(second.id()));
        assertEquals(3,captured.size());
        var messages=captured.getLast().messages();
        for(String content:List.of("旧会话请求","旧会话回答","第一轮请求","已读取最近项目","第二轮请求"))
            assertEquals(1,messages.stream().filter(m->m.content().equals(content)).count(),content);
        var publicEvents=events.findByRun(first.id());
        assertTrue(publicEvents.stream().anyMatch(e->e.type().equals("TOOL_COMPLETED")));
        assertFalse(publicEvents.stream().filter(e->e.type().equals("ANSWER_DELTA")).anyMatch(e->e.payload().toString().contains("工具前说明")));
        assertEquals(1,publicEvents.stream().filter(e->e.type().equals("RUN_COMPLETED")).count());
        for(int i=0;i<publicEvents.size();i++) assertEquals(i+1,publicEvents.get(i).sequence());
        int count=publicEvents.size();
        coordinator.executeRun(thread,first.id(),"第一轮请求",null);
        assertEquals(count,events.findByRun(first.id()).size());
        String body=jdbc.queryForObject("SELECT execution_request FROM ga_executions WHERE run_id=?",String.class,first.id());
        var duplicate=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(properties.getBaseUrl()+"/internal/v1/global-assistant/executions"))
                .header("X-Spec-Agent-Internal-Token",properties.getInternalSecret()).header("Content-Type","application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(409,duplicate.statusCode()); assertEquals(3,captured.size());
        assertTrue(jdbc.queryForObject("SELECT completed_checkpoint_id IS NOT NULL FROM ga_checkpoint_heads WHERE thread_id=?",Boolean.class,thread));
    }
    @Test void cancellationFencesLateModelResultAndDoesNotLaunchTools() throws Exception {
        startPython();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(transport.completeNativeGa(any(),any(),any(),any(),any(),any())).thenAnswer(invocation -> {
            entered.countDown(); assertTrue(release.await(10,TimeUnit.SECONDS));
            return new GaModelContract.Response(GaModelContract.VERSION,"",List.of(
                    new GaModelContract.ToolCall("late-c1","project_list_recent",Map.of("limit",1))),"tool_calls",new GaModelContract.Usage(3,2));
        });
        var run=application.createRun(thread,"取消测试",null);
        try {
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            runs.requestCancel(run.id()); awaitTerminal(run.id());
            assertEquals(GlobalAssistantRunStatus.CANCELLED,runs.findById(run.id()).orElseThrow().status());
        } finally { release.countDown(); }
        assertEquals(0,jdbc.queryForObject("SELECT tool_calls FROM ga_executions WHERE run_id=?",Integer.class,run.id()));
        assertEquals(1,events.findByRun(run.id()).stream().filter(e->e.type().equals("RUN_CANCELLED")).count());
        assertFalse(events.findByRun(run.id()).stream().anyMatch(e->e.type().equals("RUN_COMPLETED")));
    }
    @Test void actualBrowserUiReconnectRefreshNavigationCancellationAndTurnHistory() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_GA_BROWSER_TEST")));
        startPython(); var target=configureLive(Set.of("project.get_summary","ui.navigate"));
        var project=projects.createProject("ga-live-write-test-"+thread);
        var real=new com.specagent.model.provider.HttpOpenCodeZenTransport(new com.fasterxml.jackson.databind.ObjectMapper(),
                OpenCodeZenTransport.BASE_URL,45,System.getenv().getOrDefault("SPEC_AGENT_OPENCODE_PROXY","DIRECT"));
        doAnswer(i -> {
            GaModelContract.Request request=i.getArgument(2);
            if(request.messages().stream().anyMatch(m->String.valueOf(m.content()).contains("浏览器取消验收"))) {
                com.specagent.model.contract.FragmentListener listener=i.getArgument(6);
                if(!listener.onFragment("取消前实际传输草稿")) throw new com.specagent.model.contract.StreamCancelledException("Browser test cancelled");
                for(int tick=0;tick<80;tick++) {
                    Thread.sleep(100);
                    if(!listener.onFragment(" ")) throw new com.specagent.model.contract.StreamCancelledException("Browser test cancelled");
                }
                return reply("取消前实际传输草稿"+" ".repeat(80));
            }
            return real.streamNativeGa(i.getArgument(0),i.getArgument(1),request,i.getArgument(3),i.getArgument(4),i.getArgument(5),i.getArgument(6));
        }).when(transport).streamNativeGa(any(),any(),any(),any(),any(),any(),any());
        int browserPort; try(var socket=new java.net.ServerSocket(0)) { browserPort=socket.getLocalPort(); }
        Path browserLog=Files.createTempFile("ga-browser-integration-",".log");
        var builder=new ProcessBuilder("node",Path.of("../frontend/node_modules/@playwright/test/cli.js").toAbsolutePath().toString(),
                "test","e2e/global-assistant-real.spec.ts","--workers=1");
        builder.directory(Path.of("../frontend").toFile());
        builder.environment().put("PLAYWRIGHT_BACKEND_PORT",Integer.toString(port));
        builder.environment().put("PLAYWRIGHT_PORT",Integer.toString(browserPort));
        builder.environment().put("PLAYWRIGHT_CHANNEL",System.getenv().getOrDefault("PLAYWRIGHT_CHANNEL","msedge"));
        builder.environment().put("SPEC_AGENT_GA_BROWSER_THREAD",thread.toString());
        builder.environment().put("SPEC_AGENT_GA_BROWSER_PROJECT",project.id().toString());
        builder.environment().put("SPEC_AGENT_GA_BROWSER_SCREENSHOT",Path.of("../docs/v2/evidence/GLOBAL_ASSISTANT_BROWSER_TURNS.png").toAbsolutePath().toString());
        builder.redirectErrorStream(true).redirectOutput(browserLog.toFile());
        Process browser=builder.start();
        try {
            assertTrue(browser.waitFor(270,TimeUnit.SECONDS),"Browser acceptance timed out");
            assertEquals(0,browser.exitValue(),Files.readString(browserLog));
            assertEquals(2,jdbc.queryForObject("SELECT count(*) FROM global_assistant_runs WHERE thread_id=? AND status='COMPLETED'",Integer.class,thread));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM global_assistant_runs WHERE thread_id=? AND status='CANCELLED'",Integer.class,thread));
            Files.writeString(Path.of("../docs/v2/evidence/GLOBAL_ASSISTANT_REAL_BROWSER.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                "recordedAt",Instant.now().toString(),"model",target.selectedModel(),"boundary","Chromium + actual Vue/SSE/Java broker/Python; real model summary/navigation; controlled slow stream only for cancellation; isolated test DB",
                "passed",List.of("live正文","connection offline/online recovery","per-turn process","refresh/history collapse","verified navigation","history navigation not replayed","cancel/no persisted draft"),"productionEngineChanged",false)));
        } finally {
            var children=browser.descendants().toList(); children.forEach(ProcessHandle::destroyForcibly);
            if(browser.isAlive()) { browser.destroyForcibly(); browser.waitFor(5,TimeUnit.SECONDS); }
            for(var child:children) if(child.isAlive()) child.onExit().get(5,TimeUnit.SECONDS);
            Files.deleteIfExists(browserLog);
        }
    }
    @Test void freshJavaStartupAndBrowserRestoreLegacyHistoryAndUnknownWriteWithoutReplay() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_GA_RESTART_BROWSER_TEST")));
        var legacy=create("迁移前旧引擎请求"); jdbc.update("UPDATE global_assistant_runs SET engine_version='java-legacy.v1' WHERE id=?",legacy.id()); runs.markRunning(legacy.id());
        lifecycle.completeWithAssistant(thread,legacy.id(),"迁移前旧引擎回答",null,null);
        startPython(); when(transport.completeNativeGa(any(),any(),any(),any(),any(),any())).thenReturn(reply("新引擎已完成回答"));
        var completed=application.createRun(thread,"新引擎已完成请求",null); awaitTerminal(completed.id());
        assertEquals(GlobalAssistantRunStatus.COMPLETED,runs.findById(completed.id()).orElseThrow().status());
        String boundary=jdbc.queryForObject("SELECT completed_checkpoint_id FROM ga_checkpoint_heads WHERE thread_id=?",String.class,thread);
        stopPython();
        var orphan=create("持久化写入后进程中断"); var prepared=preparation.prepare(thread,orphan.id(),"持久化写入后进程中断",null);
        UUID call=UUID.randomUUID(); executions.reserve(prepared.scope(),call,"TOOL","a".repeat(64));
        // Explicit crash-window fixture: real committed local mutation; result ledger intentionally remains UNKNOWN.
        var project=projects.createProject("ga-live-write-test-"+thread);
        events.append(orphan.id(),GlobalAssistantEventType.TOOL_STARTED,Map.of("capabilityId","project.create","toolCallId","restart-write","arguments",Map.of("title",project.title())));
        executions.unknown(prepared.scope(),call);
        Path jar=Path.of("build/libs/spec-agent-backend-0.0.1-SNAPSHOT.jar").toAbsolutePath(); assertTrue(Files.exists(jar),"Build current bootJar before restart gate");
        int hostPort; try(var socket=new java.net.ServerSocket(0)) { hostPort=socket.getLocalPort(); }
        var builder=new ProcessBuilder(Path.of(System.getProperty("java.home"),"bin","java.exe").toString(),"-jar",jar.toString(),
            "--server.port="+hostPort,"--spring.profiles.active=test","--spec.global-assistant.engine=langchain-ga.v1",
            "--spec.agent.model.gateway=fake","--spec.agent.model.inference=fake","--spec.agent.brain.enabled=false","--spec.agent.brain.worker.enabled=false",
            "--spec.agent.retrieval.embedding.worker.enabled=false","--spec.agent.skill.builtin.seed-enabled=false","--spec.agent.skill.local-mirror-enabled=false");
        // Credentials are environment-only; the child is restricted to the explicitly named test database.
        builder.environment().put("SPEC_AGENT_DB_NAME","spec_agent_test");
        builder.environment().put("SPEC_AGENT_BRAIN_INTERNAL_SECRET",properties.getInternalSecret());
        Path hostLog=Files.createTempFile("ga-restart-host-",".log"); builder.redirectErrorStream(true).redirectOutput(hostLog.toFile());
        Process host=builder.start();
        try {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);
            try(var client=HttpClient.newHttpClient()) {
                while(System.nanoTime()<until && host.isAlive()) {
                    try { if(client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+hostPort+"/actuator/health")).timeout(Duration.ofSeconds(1)).GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200) break; }
                    catch(Exception ignored) {} Thread.sleep(100);
                }
            }
            assertTrue(host.isAlive(),"Fresh host startup failed: "+Files.readString(hostLog));
            awaitTerminal(orphan.id());
            assertEquals(GlobalAssistantRunStatus.FAILED,runs.findById(orphan.id()).orElseThrow().status());
            assertEquals(boundary,jdbc.queryForObject("SELECT completed_checkpoint_id FROM ga_checkpoint_heads WHERE thread_id=?",String.class,thread));
            assertEquals("UNKNOWN",jdbc.queryForObject("SELECT status FROM ga_execution_calls WHERE call_id=? AND run_id=?",String.class,call,orphan.id()));
            assertEquals("java-legacy.v1",jdbc.queryForObject("SELECT engine_version FROM global_assistant_runs WHERE id=?",String.class,legacy.id()));
            assertEquals("langchain-ga.v1",jdbc.queryForObject("SELECT engine_version FROM global_assistant_runs WHERE id=?",String.class,completed.id()));
            int browserPort; try(var socket=new java.net.ServerSocket(0)) { browserPort=socket.getLocalPort(); }
            var browserBuilder=new ProcessBuilder("node",Path.of("../frontend/node_modules/@playwright/test/cli.js").toAbsolutePath().toString(),"test","e2e/global-assistant-recovery.spec.ts","--workers=1");
            browserBuilder.directory(Path.of("../frontend").toFile());
            browserBuilder.environment().put("PLAYWRIGHT_BACKEND_PORT",Integer.toString(hostPort)); browserBuilder.environment().put("PLAYWRIGHT_PORT",Integer.toString(browserPort));
            browserBuilder.environment().put("PLAYWRIGHT_CHANNEL","msedge"); browserBuilder.environment().put("SPEC_AGENT_GA_RECOVERY_THREAD",thread.toString());
            browserBuilder.environment().put("SPEC_AGENT_GA_RECOVERY_RUN",orphan.id().toString());
            browserBuilder.environment().put("SPEC_AGENT_GA_RECOVERY_SCREENSHOT",Path.of("../docs/v2/evidence/GLOBAL_ASSISTANT_BROWSER_RECOVERY.png").toAbsolutePath().toString());
            Path browserLog=Files.createTempFile("ga-recovery-browser-",".log"); browserBuilder.redirectErrorStream(true).redirectOutput(browserLog.toFile());
            Process browser=browserBuilder.start();
            try { assertTrue(browser.waitFor(90,TimeUnit.SECONDS)); assertEquals(0,browser.exitValue(),Files.readString(browserLog)); }
            finally { var children=browser.descendants().toList(); children.forEach(ProcessHandle::destroyForcibly); browser.destroyForcibly(); browser.waitFor(5,TimeUnit.SECONDS); deleteOwnedLog(browserLog); }
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM projects WHERE title=?",Integer.class,project.title()));
            assertEquals(1,events.findByRun(orphan.id()).stream().filter(e->e.type().equals("RUN_FAILED")).count());
            Files.writeString(Path.of("../docs/v2/evidence/GLOBAL_ASSISTANT_REAL_RESTART_BROWSER.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
                "recordedAt",Instant.now().toString(),"status","PASS","boundary","fresh packaged Java JVM startup recovery + actual browser; isolated test DB; controlled model; explicitly seeded committed-write/UNKNOWN crash window; original fixture host dormant",
                "completedCheckpointPreserved",true,"legacyHistoryPreserved",true,"unknownWriteNotReplayed",true,"productionEngineChanged",false)));
        } finally { host.descendants().forEach(ProcessHandle::destroyForcibly); host.destroyForcibly(); host.waitFor(5,TimeUnit.SECONDS); deleteOwnedLog(hostLog); }
    }

    @Test void liveLongConversationSummaryContinuityAndInjectedSummaryFailureRecoverFromCompletedBoundary() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_GA_LIVE_LONG_CONVERSATION")));
        startPython();
        var target=configureLive(Set.of("project.get_summary"));
        var project=projects.createProject("ga-live-write-test-"+thread);
        var real=new com.specagent.model.provider.HttpOpenCodeZenTransport(new com.fasterxml.jackson.databind.ObjectMapper(),
                OpenCodeZenTransport.BASE_URL,45,System.getenv().getOrDefault("SPEC_AGENT_OPENCODE_PROXY","DIRECT"));
        var captured=new CopyOnWriteArrayList<GaModelContract.Request>();
        var injected=new java.util.concurrent.atomic.AtomicBoolean();
        var failedRun=new java.util.concurrent.atomic.AtomicReference<UUID>();
        var completedBoundary=new java.util.concurrent.atomic.AtomicReference<String>();
        doAnswer(i -> {
            GaModelContract.Request request=i.getArgument(2); captured.add(request);
            if("SUMMARY".equals(request.callType()) && injected.compareAndSet(false,true)) {
                failedRun.set(request.runId());
                completedBoundary.set(jdbc.queryForObject("SELECT completed_checkpoint_id FROM ga_checkpoint_heads WHERE thread_id=?",String.class,thread));
                throw new IllegalStateException("Injected summary transport failure; provider not called for this attempt");
            }
            return real.completeNativeGa(i.getArgument(0),i.getArgument(1),request,i.getArgument(3),i.getArgument(4),i.getArgument(5));
        }).when(transport).completeNativeGa(any(),any(),any(),any(),any(),any());
        doAnswer(i -> {
            GaModelContract.Request request=i.getArgument(2); captured.add(request);
            return real.streamNativeGa(i.getArgument(0),i.getArgument(1),request,i.getArgument(3),i.getArgument(4),i.getArgument(5),i.getArgument(6));
        }).when(transport).streamNativeGa(any(),any(),any(),any(),any(),any(),any());
        long began=System.nanoTime(); int completed=0;
        for(int round=0;round<35;round++) {
            String prompt=round==0 ? "请调用 project.get_summary，projectId 为 "+project.id()+"，读取项目概览。记住这个项目的真实 UUID，后续会核对。简短回答。"
                :round==34 ? "请只写最初读取项目的真实 UUID，用于验证长对话连续性；不要再次调用工具。"
                :"连续性验收第"+round+"轮，只回答已记录；保持最初项目身份，不要调用工具。";
            var run=application.createRun(thread,prompt,null);
            awaitLiveTerminal(run.id());
            if(run.id().equals(failedRun.get())) {
                UUID failureId=run.id();
                assertEquals(GlobalAssistantRunStatus.FAILED,runs.findById(run.id()).orElseThrow().status(),failure(run.id()));
                assertEquals(completedBoundary.get(),jdbc.queryForObject("SELECT completed_checkpoint_id FROM ga_checkpoint_heads WHERE thread_id=?",String.class,thread));
                assertEquals(1,captured.stream().filter(r->r.runId().equals(failureId) && r.callType().equals("SUMMARY")).count());
                var failureMessages=conversations.listMessages(thread).stream().filter(m->failureId.equals(m.runId()) && m.role().name().equals("ASSISTANT")).toList();
                assertEquals(1,failureMessages.size());
                assertTrue(failureMessages.getFirst().content().startsWith("这一步未能完成"));
                assertEquals(1,events.findByRun(failureId).stream().filter(e->e.type().equals("RUN_FAILED")).count());
                run=application.createRun(thread,prompt,null); awaitLiveTerminal(run.id());
            }
            assertEquals(GlobalAssistantRunStatus.COMPLETED,runs.findById(run.id()).orElseThrow().status(),failure(run.id()));
            completed++;
            if(round==34) assertTrue(conversations.listMessages(thread).getLast().content().contains(project.id().toString()));
        }
        assertTrue(injected.get());
        var summaries=captured.stream().filter(r->r.callType().equals("SUMMARY")).toList();
        assertTrue(summaries.size()>=2); assertTrue(summaries.stream().allMatch(r->r.tools().isEmpty() && r.toolChoice().equals("none")));
        assertEquals(72,conversations.listMessages(thread).size());
        assertEquals(35,completed);
        Files.writeString(Path.of("../docs/v2/evidence/GLOBAL_ASSISTANT_REAL_LONG_SUMMARY.json"),new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of(
            "recordedAt",Instant.now().toString(),"boundary","35 actual model conversation turns + real framework SUMMARY via Java broker/Python/checkpoint; isolated test DB",
            "model",target.selectedModel(),"completedTurns",completed,"summaryAttempts",summaries.size(),"injectedSummaryTransportFailures",1,
            "completedBoundaryPreservedOnFailure",true,"publicMessages",72,"elapsedMs",TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-began),"productionEngineChanged",false)));
    }
    private void awaitLiveTerminal(UUID run) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(180);
        while(System.nanoTime()<until && runs.findById(run).orElseThrow().status().isActive()) Thread.sleep(50);
        assertTrue(runs.findById(run).orElseThrow().status().isTerminal(),failure(run));
    }
    @Test void longConversationUsesSharedHostSummaryBudgetAndKeepsPublicHistory() throws Exception {
        startPython();
        var captured=new CopyOnWriteArrayList<GaModelContract.Request>();
        when(transport.completeNativeGa(any(),any(),any(),any(),any(),any())).thenAnswer(invocation -> {
            GaModelContract.Request request=invocation.getArgument(2); captured.add(request);
            return reply("SUMMARY".equals(request.callType())?"用户持续进行应用对话":"已回答");
        });
        for(int i=0;i<35;i++) {
            var run=application.createRun(thread,"长会话第"+i+"轮",null); awaitTerminal(run.id());
            assertEquals(GlobalAssistantRunStatus.COMPLETED,runs.findById(run.id()).orElseThrow().status(),failure(run.id()));
        }
        var summaries=captured.stream().filter(r->r.callType().equals("SUMMARY")).toList();
        assertEquals(1,summaries.size());
        assertTrue(summaries.getFirst().tools().isEmpty()); assertEquals("none",summaries.getFirst().toolChoice());
        assertEquals(2,jdbc.queryForObject("SELECT model_calls FROM ga_executions WHERE run_id=?",Integer.class,summaries.getFirst().runId()));
        assertEquals(70,conversations.listMessages(thread).size());
        assertTrue(captured.getLast().messages().size()<36);
        assertEquals(35,jdbc.queryForObject("SELECT count(*) FROM global_assistant_runs WHERE thread_id=? AND status='COMPLETED'",Integer.class,thread));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"cancel","deadline","model-failure","python-disconnect"})
    void streamedDraftIsFencedAndTerminalizesExactlyOnce(String fault) throws Exception {
        startPython();
        var entered=new CountDownLatch(1); var release=new CountDownLatch(1);
        doAnswer(i -> {
            com.specagent.model.contract.FragmentListener listener=i.getArgument(6);
            assertTrue(listener.onFragment("仍在生成的草稿")); entered.countDown();
            assertTrue(release.await(12,TimeUnit.SECONDS));
            if(fault.equals("model-failure")) throw new IllegalArgumentException("Simulated malformed provider termination");
            listener.onFragment("不得迟到追加");
            return reply("仍在生成的草稿不得迟到追加");
        }).when(transport).streamNativeGa(any(),any(),any(),any(),any(),any(),any());
        var run=application.createRun(thread,"验证流中"+fault,null);
        try {
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<until && events.findByRun(run.id()).stream().noneMatch(e->e.type().equals("ANSWER_DELTA"))) Thread.sleep(10);
            assertTrue(events.findByRun(run.id()).stream().anyMatch(e->e.type().equals("ANSWER_DELTA")),failure(run.id()));
            assertEquals(GlobalAssistantRunStatus.RUNNING,runs.findById(run.id()).orElseThrow().status());
            assertEquals(1,conversations.listMessages(thread).size());
            if(fault.equals("cancel")) runs.requestCancel(run.id());
            if(fault.equals("deadline")) jdbc.update("UPDATE ga_executions SET deadline=clock_timestamp()-interval '1 second' WHERE run_id=?",run.id());
            if(fault.equals("python-disconnect")) stopPython();
            if(fault.equals("model-failure")) release.countDown();
            awaitTerminal(run.id());
            assertEquals(fault.equals("cancel")?GlobalAssistantRunStatus.CANCELLED:GlobalAssistantRunStatus.FAILED,
                    runs.findById(run.id()).orElseThrow().status());
        } finally { release.countDown(); }
        var publicEvents=events.findByRun(run.id());
        var terminal=publicEvents.stream().filter(e->Set.of("RUN_CANCELLED","RUN_FAILED","RUN_COMPLETED").contains(e.type())).toList();
        assertEquals(1,terminal.size());
        assertTrue(publicEvents.stream().noneMatch(e->e.type().equals("ANSWER_DELTA") && e.sequence()>terminal.getFirst().sequence()));
        assertFalse(conversations.listMessages(thread).stream().anyMatch(m->m.content().contains("仍在生成的草稿")));
        assertNull(jdbc.queryForObject("SELECT completed_checkpoint_id FROM ga_checkpoint_heads WHERE thread_id=?",String.class,thread));
        assertEquals(0,jdbc.queryForObject("SELECT tool_calls FROM ga_executions WHERE run_id=?",Integer.class,run.id()));
        int count=publicEvents.size();
        completion.failed(run.id(),"test-model"); coordinator.executeRun(thread,run.id(),"验证流中"+fault,null);
        assertEquals(count,events.findByRun(run.id()).size());
    }
    @Test void userInputAndModelFailureMapToExistingProductTerminals() throws Exception {
        startPython();
        when(transport.completeNativeGa(any(),any(),any(),any(),any(),any())).thenReturn(new GaModelContract.Response(
                GaModelContract.VERSION,"",List.of(new GaModelContract.ToolCall("question-c1","user-input_request",
                Map.of("question","请选择目标项目"))),"tool_calls",new GaModelContract.Usage(3,2)));
        var question=application.createRun(thread,"帮我打开项目",null); awaitTerminal(question.id());
        assertEquals(GlobalAssistantRunStatus.COMPLETED,runs.findById(question.id()).orElseThrow().status(),failure(question.id()));
        assertEquals(1,events.findByRun(question.id()).stream().filter(e->e.type().equals("USER_INPUT_REQUIRED")).count());
        assertEquals(1,jdbc.queryForObject("SELECT model_calls FROM ga_executions WHERE run_id=?",Integer.class,question.id()));
        String checkpoint=jdbc.queryForObject("SELECT completed_checkpoint_id FROM ga_checkpoint_heads WHERE thread_id=?",String.class,thread);
        when(transport.completeNativeGa(any(),any(),any(),any(),any(),any())).thenThrow(new UnsupportedOperationException("UNSUPPORTED_AGENT_MODEL"));
        var failed=application.createRun(thread,"模型失败测试",null); awaitTerminal(failed.id());
        assertEquals(GlobalAssistantRunStatus.FAILED,runs.findById(failed.id()).orElseThrow().status());
        assertEquals(1,events.findByRun(failed.id()).stream().filter(e->e.type().equals("RUN_FAILED")).count());
        assertEquals(checkpoint,jdbc.queryForObject("SELECT completed_checkpoint_id FROM ga_checkpoint_heads WHERE thread_id=?",String.class,thread));
        assertEquals(0,jdbc.queryForObject("SELECT tool_calls FROM ga_executions WHERE run_id=?",Integer.class,failed.id()));
    }
    @Test void toolFailureIsRecordedAsObservationWithoutExposingProtocolAsAnswer() throws Exception {
        startPython();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(transport.completeNativeGa(any(),any(),any(),any(),any(),any())).thenAnswer(i -> {
            if(calls.getAndIncrement()==0) return new GaModelContract.Response(GaModelContract.VERSION,"",List.of(
                    new GaModelContract.ToolCall("missing-project","project_get_summary",Map.of("projectId",UUID.randomUUID().toString()))),
                    "tool_calls",new GaModelContract.Usage(3,2));
            GaModelContract.Request request=i.getArgument(2);
            assertTrue(request.messages().getLast().content().contains("FAILED"));
            return reply("目标项目不存在，请确认项目。该查询没有修改数据。");
        });
        var run=application.createRun(thread,"查不存在的项目",null); awaitTerminal(run.id());
        assertEquals(GlobalAssistantRunStatus.COMPLETED,runs.findById(run.id()).orElseThrow().status(),failure(run.id()));
        assertEquals(1,events.findByRun(run.id()).stream().filter(e->e.type().equals("TOOL_FAILED")).count());
        assertEquals(1,events.findByRun(run.id()).stream().filter(e->e.type().equals("RUN_COMPLETED")).count());
        assertEquals(2,jdbc.queryForObject("SELECT model_calls FROM ga_executions WHERE run_id=?",Integer.class,run.id()));
        assertTrue(events.findByRun(run.id()).stream().filter(e->e.type().equals("ANSWER_DELTA"))
                .noneMatch(e->e.payload().toString().contains("ga-capability-result.v1")));
    }

    @Test void publicSseDisconnectDoesNotCancelAndCursorReplayCompletesWithoutDuplicatingDraft() throws Exception {
        startPython();
        var release=new CountDownLatch(1);
        doAnswer(i -> {
            com.specagent.model.contract.FragmentListener listener=i.getArgument(6);
            listener.onFragment("第一片正文");
            assertTrue(release.await(10,TimeUnit.SECONDS));
            listener.onFragment("第二片正文"); return reply("第一片正文第二片正文");
        }).when(transport).streamNativeGa(any(),any(),any(),any(),any(),any(),any());
        var run=application.createRun(thread,"SSE重连验证",null);
        int cursor=0;
        try {
            var response=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+
                    "/api/v1/global-assistant/runs/"+run.id()+"/events")).header("Accept","text/event-stream")
                    .timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            assertEquals(200,response.statusCode());
            try(var reader=new java.io.BufferedReader(new java.io.InputStreamReader(response.body(),java.nio.charset.StandardCharsets.UTF_8))) {
                String line;
                while((line=reader.readLine())!=null) {
                    if(line.startsWith("id:")) cursor=Integer.parseInt(line.substring(3).trim());
                    if(line.startsWith("data:") && line.contains("ANSWER_DELTA")) {
                        assertTrue(line.contains("第一片正文")); break;
                    }
                }
            }
            assertTrue(cursor>0);
            assertEquals(GlobalAssistantRunStatus.RUNNING,runs.findById(run.id()).orElseThrow().status());
        } finally { release.countDown(); }
        awaitTerminal(run.id());
        var replay=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+
                "/api/v1/global-assistant/runs/"+run.id()+"/events")).header("Accept","text/event-stream")
                .header("Last-Event-ID",Integer.toString(cursor)).timeout(Duration.ofSeconds(10)).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(200,replay.statusCode());
        assertTrue(replay.body().contains("第二片正文")); assertFalse(replay.body().contains("第一片正文"));
        assertTrue(replay.body().contains("RUN_COMPLETED"));
        assertEquals("第一片正文第二片正文",conversations.listMessages(thread).getLast().content());
    }
    @Test void steerHandsOffOnceWithoutResumingInterruptedCheckpoint() throws Exception {
        startPython();
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(transport.completeNativeGa(any(),any(),any(),any(),any(),any())).thenAnswer(invocation -> {
            if(calls.getAndIncrement()==0) { entered.countDown(); assertTrue(release.await(10,TimeUnit.SECONDS)); return reply("旧回答"); }
            return reply("新一轮完成");
        });
        var old=application.createRun(thread,"原始任务",null);
        try {
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            var accepted=application.steerRun(old.id(),"新任务",null);
            awaitTerminal(old.id());
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            UUID successor=null;
            while(System.nanoTime()<until && successor==null) {
                successor=jdbc.queryForObject("SELECT successor_run_id FROM global_assistant_pending_turns WHERE id=?",UUID.class,accepted.pending().id());
                if(successor==null) Thread.sleep(50);
            }
            assertNotNull(successor); awaitTerminal(successor);
            assertEquals(GlobalAssistantRunStatus.CANCELLED,runs.findById(old.id()).orElseThrow().status());
            assertEquals(GlobalAssistantRunStatus.COMPLETED,runs.findById(successor).orElseThrow().status(),failure(successor));
            assertEquals(1,conversations.listMessages(thread).stream().filter(m->m.role()==GlobalAssistantMessage.Role.USER && m.content().equals("新任务")).count());
            assertEquals(0,conversations.listMessages(thread).stream().filter(m->m.content().equals("旧回答")).count());
            assertEquals(2,calls.get());
        } finally { release.countDown(); }
    }
    @Test void pythonCrashKeepsCompletedBoundaryAndNextRunStartsFresh() throws Exception {
        startPython();
        var first=application.createRun(thread,"已完成请求",null); awaitTerminal(first.id());
        assertEquals(GlobalAssistantRunStatus.COMPLETED,runs.findById(first.id()).orElseThrow().status(),failure(first.id()));
        String boundary=jdbc.queryForObject("SELECT completed_checkpoint_id FROM ga_checkpoint_heads WHERE thread_id=?",String.class,thread);
        CountDownLatch entered=new CountDownLatch(1),release=new CountDownLatch(1);
        when(transport.completeNativeGa(any(),any(),any(),any(),any(),any())).thenAnswer(invocation -> {
            entered.countDown(); assertTrue(release.await(10,TimeUnit.SECONDS)); return reply("不可落地的回答");
        });
        var interrupted=application.createRun(thread,"中断请求",null);
        try {
            assertTrue(entered.await(10,TimeUnit.SECONDS));
            stopPython(); awaitTerminal(interrupted.id());
            assertEquals(GlobalAssistantRunStatus.FAILED,runs.findById(interrupted.id()).orElseThrow().status());
            assertEquals(boundary,jdbc.queryForObject("SELECT completed_checkpoint_id FROM ga_checkpoint_heads WHERE thread_id=?",String.class,thread));
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM ga_checkpoints WHERE thread_id=?",Integer.class,thread)>0);
        } finally { release.countDown(); }
        Files.deleteIfExists(output); output=null;
        when(transport.completeNativeGa(any(),any(),any(),any(),any(),any())).thenReturn(reply("重启后新请求完成"));
        startPython();
        var next=application.createRun(thread,"重启后新请求",null); awaitTerminal(next.id());
        assertEquals(GlobalAssistantRunStatus.COMPLETED,runs.findById(next.id()).orElseThrow().status(),failure(next.id()));
        assertFalse(conversations.listMessages(thread).stream().anyMatch(m->m.content().equals("不可落地的回答")));
        application.deleteThread(thread);
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ga_checkpoints WHERE thread_id=?",Integer.class,thread));
        assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM ga_executions WHERE thread_id=?",Integer.class,thread));
    }
    @Test void liveConfiguredModelCompletesActualProductDispatchWithReadOnlyCapability() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_GA_LIVE_QUALIFICATION")));
        startPython();
        var target=configureLive(Set.of("project.list_recent"));
        var evidence=new LinkedHashMap<String,Object>();
        evidence.put("recordedAt",Instant.now().toString()); evidence.put("model",target.selectedModel());
        evidence.put("scope","product application + dispatcher + coordinator + Python execution HTTP + real model + actual read-only capability + checkpoint + product events; isolated test database");
        evidence.put("productionAcceptance","NOT_RUN"); evidence.put("providerTokenStreaming","IMPLEMENTED_NOT_YET_OBSERVED");
        try {
            var run=application.createRun(thread,"请调用 project.list_recent，limit=1，查看最近项目。只查询一次，用约200字中文说明查询结果和可执行的下一步；不要编造项目事实。",null);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(100);
            boolean beforeModelFinished=false;
            while(System.nanoTime()<until && runs.findById(run.id()).orElseThrow().status().isActive()) {
                if(events.findByRun(run.id()).stream().anyMatch(e->e.type().equals("ANSWER_DELTA"))
                        && jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM ga_execution_calls c JOIN ga_executions e ON e.run_id=c.run_id WHERE c.run_id=? AND c.kind='MODEL' AND c.status='RESERVED' AND e.tool_calls>=1)",Boolean.class,run.id()))
                    beforeModelFinished=true;
                Thread.sleep(10);
            }
            assertEquals(GlobalAssistantRunStatus.COMPLETED,runs.findById(run.id()).orElseThrow().status(),failure(run.id()));
            assertTrue(jdbc.queryForObject("SELECT tool_calls FROM ga_executions WHERE run_id=?",Integer.class,run.id())>=1);
            assertEquals(1,events.findByRun(run.id()).stream().filter(e->e.type().equals("RUN_COMPLETED")).count());
            assertTrue(jdbc.queryForObject("SELECT completed_checkpoint_id IS NOT NULL FROM ga_checkpoint_heads WHERE thread_id=?",Boolean.class,thread));
            assertTrue(beforeModelFinished,"No product delta observed while model call was still reserved");
            evidence.put("providerTokenStreaming","PASS_PRODUCT_DELTA_BEFORE_MODEL_COMPLETION");
            evidence.put("frontendAcceptance","NOT_RUN_IN_BROWSER");
            // Safe product envelopes can also be replayed through the real frontend projection.
            var publicReply=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+
                    "/api/v1/global-assistant/runs/"+run.id()+"/events")).header("Accept","application/json")
                    .GET().build(),HttpResponse.BodyHandlers.ofString());
            assertEquals(200,publicReply.statusCode());
            evidence.put("productEvents",new com.fasterxml.jackson.databind.ObjectMapper().readTree(publicReply.body()));
            evidence.put("finalText",conversations.listMessages(thread).getLast().content());
            evidence.put("status","PASS");
            evidence.put("modelCalls",jdbc.queryForObject("SELECT model_calls FROM ga_executions WHERE run_id=?",Integer.class,run.id()));
            evidence.put("toolCalls",jdbc.queryForObject("SELECT tool_calls FROM ga_executions WHERE run_id=?",Integer.class,run.id()));
        } catch(Exception | AssertionError ex) { evidence.put("status","FAIL"); evidence.put("failureCategory",ex.getClass().getSimpleName()); throw ex; }
        finally {
            var file=Path.of("../docs/v2/evidence/GLOBAL_ASSISTANT_PRODUCT_DISPATCH_INTEGRATION.json");
            Files.createDirectories(file.getParent()); new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(),evidence);
        }
    }
    @Test void liveConfiguredModelCreatesOneProjectWithoutDuplicateDispatchEffects() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_GA_LIVE_QUALIFICATION")));
        startPython();
        var target=configureLive(Set.of("project.create"));
        var evidence=new LinkedHashMap<String,Object>();
        evidence.put("recordedAt",Instant.now().toString()); evidence.put("model",target.selectedModel());
        evidence.put("scope","real model + actual project.create + product dispatcher/Python/checkpoint/events; isolated test database");
        evidence.put("productionAcceptance","NOT_RUN"); evidence.put("approvalPolicy","existing LOCAL_DURABLE application capability; no new approval override");
        String title="ga-live-write-test-"+thread;
        try {
            String text="请调用 project.create 创建一个项目，title 必须精确为 "+title+"。只创建一次，成功后用中文确认。";
            var run=application.createRun(thread,text,null);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(100);
            while(System.nanoTime()<until && runs.findById(run.id()).orElseThrow().status().isActive()) Thread.sleep(20);
            assertEquals(GlobalAssistantRunStatus.COMPLETED,runs.findById(run.id()).orElseThrow().status(),failure(run.id()));
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM projects WHERE title=?",Integer.class,title));
            assertEquals(1,jdbc.queryForObject("SELECT tool_calls FROM ga_executions WHERE run_id=?",Integer.class,run.id()));
            int count=events.findByRun(run.id()).size(); coordinator.executeRun(thread,run.id(),text,null);
            assertEquals(count,events.findByRun(run.id()).size());
            assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM projects WHERE title=?",Integer.class,title));
            assertEquals(1,events.findByRun(run.id()).stream().filter(e->e.type().equals("RUN_COMPLETED")).count());
            assertTrue(jdbc.queryForObject("SELECT completed_checkpoint_id IS NOT NULL FROM ga_checkpoint_heads WHERE thread_id=?",Boolean.class,thread));
            evidence.put("status","PASS"); evidence.put("modelCalls",jdbc.queryForObject("SELECT model_calls FROM ga_executions WHERE run_id=?",Integer.class,run.id()));
            evidence.put("toolCalls",1); evidence.put("projectRows",1); evidence.put("duplicateDispatchSideEffects",0);
        } catch(Exception | AssertionError failure) { evidence.put("status","FAIL"); evidence.put("failureCategory",failure.getClass().getSimpleName()); throw failure; }
        finally {
            var file=Path.of("../docs/v2/evidence/GLOBAL_ASSISTANT_PRODUCT_WRITE_INTEGRATION.json");
            Files.createDirectories(file.getParent()); new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(),evidence);
        }
    }
    @Test void liveConfiguredModelSearchesSummarizesAndNavigatesVerifiedProject() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_GA_LIVE_QUALIFICATION")));
        startPython();
        var target=configureLive(Set.of("project.search","project.get_summary","ui.navigate"));
        String title="ga-live-write-test-"+thread;
        UUID project=projects.createProject(title).id();
        runLiveCapabilityGate("GLOBAL_ASSISTANT_PRODUCT_QUERY_NAVIGATION_INTEGRATION.json",target,
                "real model + actual project.search/get_summary + host-verified UI navigation; isolated test database",
                "请依次执行三步：用 project.search 按完整标题查找项目 "+title+"；用得到的真实 projectId 调用 project.get_summary；最后 ui.navigate 打开 PROJECT 页面。不得跳过搜索或概要，完成后中文报告。",
                run -> {
                    assertEquals(3,jdbc.queryForObject("SELECT tool_calls FROM ga_executions WHERE run_id=?",Integer.class,run));
                    var actions=events.findByRun(run).stream().filter(e->e.type().equals("UI_ACTION")).toList();
                    assertEquals(1,actions.size()); assertEquals(project.toString(),actions.getFirst().payload().get("resourceId"));
                });
    }
    @Test void liveConfiguredModelUsesHostQuestionWithoutAnotherModelTurn() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_GA_LIVE_QUALIFICATION")));
        startPython();
        var target=configureLive(Set.of("user-input.request"));
        runLiveCapabilityGate("GLOBAL_ASSISTANT_PRODUCT_CLARIFICATION_INTEGRATION.json",target,
                "real model + actual bounded question capability + durable checkpoint/product terminal; isolated test database",
                "我还没有确定要选择哪个项目。请调用 user-input.request，question 为“请选择要打开的项目”，然后等待我的下一轮回答。",
                run -> {
                    assertEquals(1,jdbc.queryForObject("SELECT model_calls FROM ga_executions WHERE run_id=?",Integer.class,run));
                    assertEquals(1,events.findByRun(run).stream().filter(e->e.type().equals("USER_INPUT_REQUIRED")).count());
                });
    }
    @Test void liveConfiguredModelDiscoversAndStagesSkillWithControlledGitFixture() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_GA_LIVE_QUALIFICATION")));
        startPython();
        var target=configureLive(Set.of("skill.import.discover","skill.import"));
        byte[] markdown=("---\nname: ga-native-live-"+thread+"\ndescription: Controlled integration fixture\n---\nNever execute anything.\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var files=List.of(new com.specagent.skill.importing.SkillSourceFile("SKILL.md",markdown,
                com.specagent.skill.domain.SkillPackageFile.FileKind.SKILL_MD));
        when(gitImporter.fetchTree(anyString(),any(),any())).thenReturn(new com.specagent.skill.importing.GitSkillImporter.TreeInventory(
                "c".repeat(40),files,markdown.length,List.of()));
        when(gitImporter.importHttps(anyString(),any(),anyString())).thenReturn(new com.specagent.skill.importing.GitSkillImporter.ExtractedResult(
                "c".repeat(40),markdown,files,markdown.length));
        runLiveCapabilityGate("GLOBAL_ASSISTANT_PRODUCT_SKILL_STAGING_INTEGRATION.json",target,
                "real model + actual Skill discovery/staging services/database/RPC/checkpoint/product events; Git/network fixture, not live clone",
                "请先用 skill.import.discover 检查 https://example.org/ga-controlled.git 的 Skill。确认是唯一候选后，用 skill.import 将同一 URL 暂存给我审阅；不得安装、启用或执行。成功后用中文报告暂存编号。",
                run -> {
                    assertEquals(2,jdbc.queryForObject("SELECT tool_calls FROM ga_executions WHERE run_id=?",Integer.class,run));
                    assertEquals(1,jdbc.queryForObject("SELECT count(*) FROM skill_staged_imports WHERE manifest=? AND status='STAGED'",Integer.class,
                            new String(markdown,java.nio.charset.StandardCharsets.UTF_8)));
                    assertEquals(0,jdbc.queryForObject("SELECT count(*) FROM skills WHERE name=?",Integer.class,"ga-native-live-"+thread));
                    verify(gitImporter,never()).importHttps(anyString(),any(),anyString());
                });
    }
    private void runLiveCapabilityGate(String fileName,OpenCodeSettings target,String scope,String text,
            java.util.function.Consumer<UUID> assertions) throws Exception {
        var evidence=new LinkedHashMap<String,Object>();
        evidence.put("recordedAt",Instant.now().toString()); evidence.put("model",target.selectedModel());
        evidence.put("scope",scope); evidence.put("productionAcceptance","NOT_RUN");
        try {
            var run=application.createRun(thread,text,null);
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(100);
            while(System.nanoTime()<until && runs.findById(run.id()).orElseThrow().status().isActive()) Thread.sleep(20);
            assertEquals(GlobalAssistantRunStatus.COMPLETED,runs.findById(run.id()).orElseThrow().status(),failure(run.id()));
            assertions.accept(run.id());
            assertEquals(1,events.findByRun(run.id()).stream().filter(e->e.type().equals("RUN_COMPLETED")).count());
            assertTrue(jdbc.queryForObject("SELECT completed_checkpoint_id IS NOT NULL FROM ga_checkpoint_heads WHERE thread_id=?",Boolean.class,thread));
            evidence.put("status","PASS");
            evidence.put("modelCalls",jdbc.queryForObject("SELECT model_calls FROM ga_executions WHERE run_id=?",Integer.class,run.id()));
            evidence.put("toolCalls",jdbc.queryForObject("SELECT tool_calls FROM ga_executions WHERE run_id=?",Integer.class,run.id()));
        } catch(Exception | AssertionError failure) { evidence.put("status","FAIL"); evidence.put("failureCategory",failure.getClass().getSimpleName()); throw failure; }
        finally {
            var file=Path.of("../docs/v2/evidence/"+fileName);
            Files.createDirectories(file.getParent()); new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(),evidence);
        }
    }
    @Test void liveMimoSearchesFetchesAndCitesWebSources() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_GA_LIVE_WEB")));
        String key=System.getenv("SPEC_AGENT_TAVILY_API_KEY");
        assertTrue(key!=null && !key.isBlank(), "Tavily key required for real integration");
        var actual=new com.specagent.assistant.tool.TavilyWebService(key);
        doReturn(true).when(web).configured();
        doAnswer(i -> actual.prepare(i.getArgument(0),i.getArgument(1),i.getArgument(2)))
                .when(web).prepare(anyString(),anyMap(),any());
        startPython();
        var target=configureLive(Set.of("web.search","web.fetch"));
        var run=application.createRun(thread,"请先用 web.search 搜索 LangChain Python create_agent 官方文档，然后从搜索结果选一个官方网页调用 web.fetch 读取正文，最后用中文解释聊天模型如何调用搜索工具。必须完成搜索和读取，引用本轮返回的 [web:sourceId]，不要编造来源。",null);
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(150);
        while(System.nanoTime()<until && runs.findById(run.id()).orElseThrow().status().isActive()) Thread.sleep(50);
        assertEquals(GlobalAssistantRunStatus.COMPLETED,runs.findById(run.id()).orElseThrow().status(),failure(run.id()));
        var publicEvents=events.findByRun(run.id());
        String serialized=new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writeValueAsString(publicEvents);
        assertTrue(serialized.contains("web.search"), "Search must be dispatched");
        assertTrue(serialized.contains("web.fetch"), "Fetch must be dispatched");
        assertTrue(serialized.contains("EXTRACTED_TEXT"), "Real extraction must succeed");
        String answer=conversations.listMessages(thread).getLast().content();
        assertTrue(answer.contains("[web:"), "Answer must cite returned sources");
        assertFalse(serialized.contains(key));
        var evidence=new LinkedHashMap<String,Object>();
        evidence.put("recordedAt",Instant.now().toString()); evidence.put("model",target.selectedModel());
        evidence.put("engineVersion",jdbc.queryForObject("SELECT engine_version FROM global_assistant_runs WHERE id=?",String.class,run.id()));
        evidence.put("status","PASS"); evidence.put("scope","real configured chat model + Tavily search/extract + Java host + Python create_agent + durable events/checkpoint; test database");
        evidence.put("events",publicEvents.stream().filter(e->e.type().startsWith("RUN_") || e.type().startsWith("TOOL_")).toList()); evidence.put("answer",answer);
        evidence.put("eventSelection","Durable RUN/TOOL envelopes; streaming fragments omitted");
        var path=Path.of("../docs/v2/evidence/GLOBAL_ASSISTANT_WEB_INTEGRATION.json");
        Files.createDirectories(path.getParent()); new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules().writerWithDefaultPrettyPrinter().writeValue(path.toFile(),evidence);
    }
    private OpenCodeSettings configureLive(Set<String> allowed) {
        var source=new org.springframework.jdbc.datasource.DriverManagerDataSource(
                System.getenv().getOrDefault("SPEC_AGENT_GA_QUALIFICATION_DB_URL","jdbc:postgresql://localhost:5434/spec_agent"),
                System.getenv().getOrDefault("SPEC_AGENT_DB_USER","spec_agent"),
                System.getenv().getOrDefault("SPEC_AGENT_DB_PASSWORD","spec_agent_dev"));
        var host=new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(source);
        var crypto=new ModelCredentialCrypto(System.getenv().getOrDefault("SPEC_AGENT_SECRET_MASTER_KEY",""),"",
                System.getenv().getOrDefault("SPEC_AGENT_GA_QUALIFICATION_KEY_FILE","data/secret-master.key"));
        var target=new JdbcOpenCodeSettingsRepository(host,crypto).find().orElseThrow();
        when(settings.find()).thenReturn(Optional.of(target));
        var snapshot=catalogs.current();
        var readonly=catalogs.snapshot(snapshot.descriptors().stream().filter(d->allowed.contains(d.capabilityId())).toList());
        doReturn(readonly).when(catalogs).current();
        var real=new com.specagent.model.provider.HttpOpenCodeZenTransport(new com.fasterxml.jackson.databind.ObjectMapper(),
                OpenCodeZenTransport.BASE_URL,45,System.getenv().getOrDefault("SPEC_AGENT_OPENCODE_PROXY","DIRECT"));
        when(transport.completeNativeGa(any(),any(),any(),any(),any(),any())).thenAnswer(i->real.completeNativeGa(
                i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),i.getArgument(4),i.getArgument(5)));
        doAnswer(i->real.streamNativeGa(i.getArgument(0),i.getArgument(1),i.getArgument(2),i.getArgument(3),
                i.getArgument(4),i.getArgument(5),i.getArgument(6)))
                .when(transport).streamNativeGa(any(),any(),any(),any(),any(),any(),any());
        return target;
    }
    private GlobalAssistantRun create(String text) { return conversations.createRunWithUserMessage(thread,text,"ga-test","ga-test","ga-test"); }
    private static GaModelContract.Response reply(String text) {
        return new GaModelContract.Response(GaModelContract.VERSION,text,List.of(),"stop",new GaModelContract.Usage(3,2));
    }
    private String claim(GaExecutionPreparation.Prepared p,String hash) {
        return "{\"runId\":\""+p.scope().runId()+"\",\"executionEpoch\":1,\"leaseId\":\""+p.scope().leaseId()+"\",\"executionRequestHash\":\""+hash+"\"}";
    }
    private String failure(UUID run) {
        String safe=events.findByRun(run).stream().filter(e->e.type().equals("RUN_FAILED")).toList().toString();
        try { if(output!=null) safe+=" python="+Files.readString(output); } catch(Exception ignored) { }
        return safe;
    }
    private void awaitTerminal(UUID run) throws Exception {
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<until) { if(runs.findById(run).orElseThrow().status().isTerminal()) return; Thread.sleep(50); }
        fail("Coordinator terminal deadline");
    }
    private void startPython() throws Exception {
        Assumptions.assumeTrue("true".equals(System.getenv("SPEC_AGENT_GA_CROSS_LANGUAGE_TEST")));
        int brainPort; try(var socket=new java.net.ServerSocket(0)) { brainPort=socket.getLocalPort(); }
        Path executable=Path.of(System.getenv().getOrDefault("SPEC_AGENT_GA_TEST_PYTHON","../agent-brain/.venv/Scripts/python.exe")).toAbsolutePath();
        assertTrue(Files.isRegularFile(executable));
        var builder=new ProcessBuilder(executable.toString(),"-m","uvicorn","spec_agent_brain.app:create_app","--factory",
                "--host","127.0.0.1","--port",Integer.toString(brainPort));
        builder.directory(Path.of("../agent-brain").toFile());
        builder.environment().remove("SPEC_AGENT_TAVILY_API_KEY");
        builder.environment().put("SPEC_AGENT_BRAIN_MODEL_MODE","broker");
        builder.environment().put("PYTHONFAULTHANDLER","1");
        builder.environment().put("SPEC_AGENT_INTERNAL_BROKER_URL","http://127.0.0.1:"+port+"/internal/v1/model-inference");
        builder.environment().put("SPEC_AGENT_BRAIN_INTERNAL_SECRET",properties.getInternalSecret());
        output=Files.createTempFile("ga-coordinator-python-",".log");
        builder.redirectErrorStream(true).redirectOutput(output.toFile()); python=builder.start();
        properties.setBaseUrl("http://127.0.0.1:"+brainPort);
        var client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build();
        // Full-suite startup is independent of the execution budget; allow Windows cold imports.
        long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);
        while(System.nanoTime()<until && python.isAlive()) {
            try { if(client.send(HttpRequest.newBuilder(URI.create(properties.getBaseUrl()+"/health")).timeout(Duration.ofSeconds(1))
                    .GET().build(),HttpResponse.BodyHandlers.discarding()).statusCode()==200) return; }
            catch(Exception ignored) { }
            Thread.sleep(50);
        }
        fail("Python test executor did not become ready; alive="+python.isAlive()+" log="+Files.readString(output));
    }
    private void stopPython() throws Exception {
        if(python==null) return;
        // Windows venv launcher can own a child interpreter that inherits the log handle.
        var children=python.descendants().toList();
        children.forEach(ProcessHandle::destroyForcibly);
        python.destroyForcibly(); assertTrue(python.waitFor(5,TimeUnit.SECONDS));
        for(var child:children) if(child.isAlive()) child.onExit().get(5,TimeUnit.SECONDS);
        python=null;
    }
    private void deleteOwnedLog(Path path) throws Exception {
        for(int attempt=0;attempt<30;attempt++) {
            try { Files.deleteIfExists(path); return; }
            catch(java.nio.file.FileSystemException busy) { if(attempt==29) throw busy; Thread.sleep(100); }
        }
    }
    private void deleteTestLog() throws Exception {
        if(output==null) return;
        // Process exit can precede Windows releasing redirected log handles briefly.
        for(int attempt=0;attempt<30;attempt++) {
            try { Files.deleteIfExists(output); return; }
            catch(java.nio.file.FileSystemException busy) {
                if(attempt==29) throw busy;
                Thread.sleep(100);
            }
        }
    }
}
