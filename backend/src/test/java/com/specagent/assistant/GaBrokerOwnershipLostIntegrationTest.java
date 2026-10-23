package com.specagent.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.specagent.agent.broker.AgentBrainProperties;
import com.specagent.assistant.api.GaNativeModelBroker;
import com.specagent.assistant.conversation.ConversationDeleteService;
import com.specagent.assistant.conversation.GlobalAssistantConversationService;
import com.specagent.assistant.runtime.GaExecutionStore;
import com.specagent.model.contract.GaModelContract;
import com.specagent.model.provider.OpenCodeZenTransport;
import com.specagent.modelsettings.OpenCodeSettings;
import com.specagent.modelsettings.OpenCodeSettingsRepository;
import com.specagent.runtime.execution.ExecutionClaim;
import com.specagent.runtime.execution.ExecutionHeartbeatRegistry;
import com.specagent.runtime.execution.ExecutionToken;
import com.specagent.runtime.execution.RuntimeExecutionSettings;
import com.specagent.runtime.execution.RuntimeTaskFamily;
import com.specagent.runtime.execution.RuntimeTaskRepository;
import com.specagent.runtime.execution.RuntimeTaskScheduler;
import com.specagent.runtime.execution.RuntimeTaskState;
import com.specagent.runtime.execution.WorkerIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 文件名:GaBrokerOwnershipLostIntegrationTest.java
 *
 * 测试目标(修复 2 回归):GA 模型回调的失权判定<b>不依赖调用线程的
 * ThreadLocal</b>——真实内部 RPC 与 StreamingResponseBody 异步线程没有
 * {@code ExecutionTokenContext} 绑定,跨实例回调更看不到执行实例的内存标记。
 * 失权必须从持久化事实(runtime_tasks 行的接管/取消)与按 scope 可达的
 * 内存信号解析,数据库 token、动态授权与预算账本仍是最终权威。
 *
 * <p>场景覆盖:
 * <ol>
 *   <li><b>Worker B 执行、模型回调到 A</b>:接管已落库(runtime_tasks 行按
 *       新 epoch/lease 易主),旧 lease 的迟到回调被持久化事实拒绝,预算与
 *       账本不推进;</li>
 *   <li><b>"已判失权但旧数据库租约尚未到期"窗口</b>:执行实例心跳已把
 *       token 判失、数据库租约仍有效时,无 ThreadLocal 的 HTTP 流式线程
 *       必须立即拒绝;同一窗口内未判失的调用照常工作(不误杀合法窗口)。</li>
 * </ol>
 *
 * <p>multi-worker 开启(任务级所有权),fixture 走真实调度认领;HTTP 走
 * 真实随机端口服务;模型/传输边界打桩,所有持久化行为是真实 PostgreSQL。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spec.agent.execution.multi-worker-enabled=true",
        "spec.agent.execution.mode-admission-enabled=false",
})
class GaBrokerOwnershipLostIntegrationTest {
    @Autowired GaExecutionStore executions;
    @Autowired GlobalAssistantConversationService conversations;
    @Autowired ConversationDeleteService deletion;
    @Autowired JdbcTemplate jdbc;
    @Autowired TestRestTemplate http;
    @Autowired AgentBrainProperties properties;
    @Autowired RuntimeTaskRepository tasks;
    @Autowired com.specagent.runtime.execution.RuntimeTaskAuthorization taskAuthorization;
    @Autowired com.specagent.runtime.execution.RuntimeScopeMutex scopeMutex;
    @Autowired GaNativeModelBroker broker;
    @MockBean OpenCodeSettingsRepository settings;
    @MockBean OpenCodeZenTransport transport;
    @MockBean com.specagent.skill.importing.GitSkillImporter gitImporter;
    ObjectMapper mapper = new ObjectMapper();
    UUID thread, run, lease, binding;
    GaModelContract.Request request;
    Instant revision;
    @Autowired org.springframework.transaction.PlatformTransactionManager txManager;

    @BeforeEach void fixture() throws Exception {
        thread = conversations.createThread().id();
        lease = UUID.randomUUID();
        binding = UUID.randomUUID();
        run = conversations.createRun(thread, "ga.v1", "ga.v1", "v1").id();
        var template = GaModelContract.readRequest(Files.readString(
                Path.of("../contracts/global-assistant/fixtures/ga-model-request-valid.json")));
        var templateMessages = template.messages().stream()
                .map(message -> new GaModelContract.Message(message.role(), message.content(),
                        message.toolCalls(), message.toolCallId())).toList();
        request = new GaModelContract.Request(GaModelContract.VERSION, run, 1, lease,
                UUID.randomUUID(), "AGENT", binding, templateMessages, template.tools(),
                "auto", 1024, false);
        revision = Instant.now();
        when(settings.find()).thenReturn(Optional.of(new OpenCodeSettings(
                "test-key-no-network", "work", "test-model", revision, revision)));
        when(transport.completeNativeGa(any(), any(), any(), any(), any(), any())).thenReturn(
                new GaModelContract.Response(GaModelContract.VERSION, "rpc-complete", List.of(),
                        "stop", new GaModelContract.Usage(3, 2)));
        when(transport.streamNativeGa(any(), any(), any(), any(), any(), any(), any())).thenReturn(
                new GaModelContract.Response(GaModelContract.VERSION, "流式回答", List.of(),
                        "stop", new GaModelContract.Usage(3, 2)));
    }

    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM runtime_task_attempts WHERE task_id IN "
                + "(SELECT id FROM runtime_tasks WHERE ga_run_id=?)", run);
        jdbc.update("DELETE FROM runtime_task_capacity WHERE task_id IN "
                + "(SELECT id FROM runtime_tasks WHERE ga_run_id=?)", run);
        jdbc.update("DELETE FROM runtime_scope_owners WHERE task_id IN "
                + "(SELECT id FROM runtime_tasks WHERE ga_run_id=?)", run);
        jdbc.update("DELETE FROM runtime_task_scopes WHERE task_id IN "
                + "(SELECT id FROM runtime_tasks WHERE ga_run_id=?)", run);
        jdbc.update("DELETE FROM runtime_tasks WHERE ga_run_id=?", run);
        jdbc.update("UPDATE global_assistant_runs SET status='FAILED' WHERE id=?", run);
        deletion.deleteThread(thread);
        tasks.reconcileCapacity();
    }

    /** 真实调度认领 + 真实执行绑定初始化(与生产执行链一致)。 */
    private ExecutionClaim claimWithRealScheduler() {
        jdbc.update("""
                INSERT INTO runtime_tasks (actor_user_id, actor_org_id, actor_source, id, family, workspace_run_id, ga_run_id, task_type,
                    state, attempt_count, max_attempts, available_at, created_at, protocol_version)
                VALUES ('00000000-0000-4000-8000-000000000002'::uuid, '00000000-0000-4000-8000-000000000001'::uuid, 'LOCAL', ?, 'GLOBAL_ASSISTANT', NULL, ?, 'GA_EXECUTION',
                    'QUEUED', 0, 3, now(), now(), 1)
                """, UUID.randomUUID(), run);
        UUID taskId = jdbc.queryForObject("SELECT id FROM runtime_tasks WHERE ga_run_id=?", UUID.class, run);
        jdbc.update("INSERT INTO runtime_task_scopes (task_id, scope_key) VALUES (?, 'ga-thread:'||?)",
                taskId, thread);
        var scheduler = new RuntimeTaskScheduler(tasks, new RuntimeExecutionSettings(),
                new WorkerIdentity(), new org.springframework.transaction.support.TransactionTemplate(txManager));
        ExecutionClaim claim = scheduler.tryClaim(List.of(RuntimeTaskFamily.GLOBAL_ASSISTANT),
                List.of(), List.of(new com.specagent.assistant.runtime.GaTaskScopeResolver(tasks))).orElseThrow();
        executions.initialize(new GaExecutionStore.Scope(run, claim.token().epoch(), claim.token().leaseId()),
                new GaExecutionStore.Binding(thread, binding, "OPENCODE_ZEN", "test-model",
                        "opencode-settings:" + revision, Instant.now().plusSeconds(120), request.tools()));
        // 绑定落库后再推进业务态:initialize 要求 CREATED,授权围栏要求 RUNNING。
        jdbc.update("UPDATE global_assistant_runs SET status='RUNNING' WHERE id=?", run);
        return claim;
    }

    private ResponseEntity<String> post(String path, String body, String token) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Spec-Agent-Internal-Token", token);
        return http.postForEntity("/internal/v1/global-assistant" + path,
                new HttpEntity<>(body, headers), String.class);
    }

    /**
     * Worker B 执行、模型回调到 A:接管已落库后,旧 lease 的迟到回调必须被
     * 持久化事实拒绝——不提交、不推进预算、不新增账本行。
     */
    @Test void lateModelCallbackFromAnotherWorkerIsRefusedByPersistedOwnershipFacts() throws Exception {
        ExecutionClaim claim = claimWithRealScheduler();
        lease = claim.token().leaseId();
        var current = new GaModelContract.Request(GaModelContract.VERSION, run, 1, lease,
                UUID.randomUUID(), "AGENT", binding, request.messages(), request.tools(),
                "auto", 1024, false);
        assertEquals(200, post("/model-inference", mapper.writeValueAsString(current),
                properties.getInternalSecret()).getStatusCode().value());

        // 模拟接管提交:runtime_tasks 行按新 epoch/lease 易主(生产由 lease()
        // 递增代次并重写租约;此处直接落库接管之后的持久化事实)。
        UUID newLease = UUID.randomUUID();
        jdbc.update("UPDATE runtime_tasks SET execution_epoch=2, lease_id=? WHERE ga_run_id=?",
                newLease, run);
        var late = new GaModelContract.Request(GaModelContract.VERSION, run, 1, lease,
                UUID.randomUUID(), "AGENT", binding, request.messages(), request.tools(),
                "auto", 1024, false);
        var refused = post("/model-inference", mapper.writeValueAsString(late),
                properties.getInternalSecret());
        assertEquals(409, refused.getStatusCode().value());
        assertEquals("GA_EXECUTION_FENCE",
                mapper.readTree(refused.getBody()).path("errorCode").asText());
        // 预算不推进、无新账本行:迟到结果不能提交。
        assertEquals(1, jdbc.queryForObject(
                "SELECT model_calls FROM ga_executions WHERE run_id=?", Integer.class, run));
        assertEquals(1, jdbc.queryForObject(
                "SELECT count(*) FROM ga_execution_calls WHERE run_id=?", Integer.class, run));
    }

    /**
     * "已判失权但旧数据库租约尚未到期"的窗口:心跳判失后,无 ThreadLocal 的
     * HTTP 流式线程必须立即拒绝;同窗口内未判失时同一调用照常成功。
     */
    @Test void streamingCallbackRefusesKnownLostOwnershipWithoutThreadLocal() throws Exception {
        ExecutionClaim claim = claimWithRealScheduler();
        lease = claim.token().leaseId();
        var registry = new ExecutionHeartbeatRegistry();
        broker.setHeartbeatRegistry(registry);
        try {
            // 未登记、未判失:同一调用必须成功(合法窗口不误杀)。
            assertEquals(200, post("/model-inference/stream",
                    mapper.writeValueAsString(streamRequest()),
                    properties.getInternalSecret()).getStatusCode().value());
            registry.register(claim.token());
            assertEquals(200, post("/model-inference/stream",
                    mapper.writeValueAsString(streamRequest()),
                    properties.getInternalSecret()).getStatusCode().value());
            // 心跳判失:旧租约尚未到期,流式回调必须在响应头提交前被拒绝
            // (409,无 NDJSON 通道)——绝不让失权执行建立流式连接。
            registry.markOwnershipLost(claim.token());
            var refused = post("/model-inference/stream",
                    mapper.writeValueAsString(streamRequest()), properties.getInternalSecret());
            assertEquals(409, refused.getStatusCode().value());
            // 失权路径绝不能触碰 provider:只有前两次成功调用到达传输层。
            org.mockito.Mockito.verify(transport, org.mockito.Mockito.times(2))
                    .streamNativeGa(any(), any(), any(), any(), any(), any(), any());
        } finally {
            broker.setHeartbeatRegistry(null);
            registry.unregister(claim.token());
        }
    }

    private GaModelContract.Request streamRequest() {
        return new GaModelContract.Request(GaModelContract.VERSION, run, 1, lease,
                UUID.randomUUID(), "AGENT", binding, request.messages(), request.tools(),
                "auto", 1024, true);
    }
}
