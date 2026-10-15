package com.specagent.agent.broker;

import com.specagent.agent.broker.ModelInferenceHttpRequest;
import com.specagent.agent.protocol.AgentProtocol;
import com.specagent.agent.runevent.AgentRunEventRepository;
import com.specagent.agent.runtime.RunService;
import com.specagent.common.Json;
import com.specagent.workspace.project.Project;
import com.specagent.workspace.project.ProjectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 文件名:InternalModelInferenceBrokerIntegrationTest.java
 *
 * 测试目标:内部模型推理 broker 的安全属性——共享密钥认证、封闭的调用类型集合、
 * 有界的 prompt、脱敏的事件,以及响应中不出现任何凭证材料。使用 test profile 下的
 * 确定性假推理网关。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class InternalModelInferenceBrokerIntegrationTest {

    private static final String TOKEN = "dev-internal-secret";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private RunService runService;
    @Autowired
    private AgentRunEventRepository eventRepository;
    @Autowired
    private Json json;
    @Autowired private WorkspaceRpcGrants grants;
    @Autowired private com.specagent.agent.runtime.WorkspaceRpcSourceAuthorization sourceAuthorization;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired private com.specagent.agent.runtime.WorkspaceExecutionAuthorization executionAuthorization;
    @Autowired private org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired private javax.sql.DataSource dataSource;
    private String ticket(UUID run,String type) {
        var result=new java.util.concurrent.atomic.AtomicReference<String>();
        com.specagent.agent.runtime.WorkspaceExecutionContext.runWith(run,()->result.set(
                grants.issue(run,type,2,java.time.Duration.ofSeconds(120)).value()));
        return result.get();
    }

    private String body(UUID runId, String callType, int promptChars) {
        ModelInferenceHttpRequest request = new ModelInferenceHttpRequest(
                AgentProtocol.INFERENCE_PROTOCOL_VERSION,
                runId,
                callType,
                List.of(new ModelInferenceHttpRequest.Message("system", "s"),
                        new ModelInferenceHttpRequest.Message("user", "x".repeat(promptChars))),
                2048);
        return json.write(request);
    }

    @Test
    void rejectsMissingOrWrongInternalToken() throws Exception {
        UUID runId = newRun();
        mockMvc.perform(post("/internal/v1/model-inference")
                        .contentType("application/json")
                        .content(body(runId, "STATE_UPDATE", 10)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/v1/model-inference")
                        .header(AgentProtocol.INTERNAL_TOKEN_HEADER, "wrong")
                        .contentType("application/json")
                        .content(body(runId, "STATE_UPDATE", 10)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void completesWithFakeGatewayAndRecordsSanitizedEvent() throws Exception {
        UUID runId = newRun();
        String response = mockMvc.perform(post("/internal/v1/model-inference")
                        .header(AgentProtocol.INTERNAL_TOKEN_HEADER, TOKEN)
                        .header(WorkspaceRpcGrants.HEADER,ticket(runId,"DECISION"))
                        .contentType("application/json")
                        .content(body(runId, "DECISION", 10)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(response)
                .contains("\"protocolVersion\":\"model-inference.v1\"")
                .contains("REQUEST_USER_INPUT")
                // 凭证材料绝不跨过 broker 边界。
                .doesNotContain("apiKey")
                .doesNotContain("sk-");

        String events = eventText(runId);
        assertThat(events).contains("MODEL_INFERENCE");
        assertThat(events).contains("promptSha256");
        // 事件已脱敏:原始 prompt 内容绝不被记录。
        assertThat(events).doesNotContain("xxxxxxxxxx");
    }

    @Test void serviceSecretAloneCannotUseEvenAnExistingRun() throws Exception {
        UUID run=newRun();
        mockMvc.perform(post("/internal/v1/model-inference").header(AgentProtocol.INTERNAL_TOKEN_HEADER,TOKEN)
                .contentType("application/json").content(body(run,"DECISION",10))).andExpect(status().isForbidden());
        assertThat(eventText(run)).doesNotContain("MODEL_INFERENCE");
    }
    @Test void grantCannotBeBorrowedByAnotherRunOrAnotherCallType() throws Exception {
        UUID first=newRun(),second=newRun();String grant=ticket(first,"DECISION");
        mockMvc.perform(post("/internal/v1/model-inference").header(AgentProtocol.INTERNAL_TOKEN_HEADER,TOKEN)
                .header(WorkspaceRpcGrants.HEADER,grant).contentType("application/json").content(body(second,"DECISION",10)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/internal/v1/model-inference").header(AgentProtocol.INTERNAL_TOKEN_HEADER,TOKEN)
                .header(WorkspaceRpcGrants.HEADER,grant).contentType("application/json").content(body(first,"STATE_UPDATE",10)))
                .andExpect(status().isForbidden());
        assertThat(eventText(first)+eventText(second)).doesNotContain("MODEL_INFERENCE");
    }
    @Test void samePayloadCannotBeReissuedAndTheGrantBudgetNeverResets() throws Exception {
        UUID run=newRun();String grant=ticket(run,"DECISION");
        mockMvc.perform(post("/internal/v1/model-inference").header(AgentProtocol.INTERNAL_TOKEN_HEADER,TOKEN)
                .header(WorkspaceRpcGrants.HEADER,grant).contentType("application/json").content(body(run,"DECISION",10)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/internal/v1/model-inference").header(AgentProtocol.INTERNAL_TOKEN_HEADER,TOKEN)
                .header(WorkspaceRpcGrants.HEADER,grant).contentType("application/json").content(body(run,"DECISION",10)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/internal/v1/model-inference").header(AgentProtocol.INTERNAL_TOKEN_HEADER,TOKEN)
                .header(WorkspaceRpcGrants.HEADER,grant).contentType("application/json").content(body(run,"DECISION",11)))
                .andExpect(status().isOk());
        mockMvc.perform(post("/internal/v1/model-inference").header(AgentProtocol.INTERNAL_TOKEN_HEADER,TOKEN)
                .header(WorkspaceRpcGrants.HEADER,grant).contentType("application/json").content(body(run,"DECISION",12)))
                .andExpect(status().isForbidden());
        assertThat(jdbc.queryForObject("SELECT consumed_calls FROM workspace_rpc_grants WHERE run_id=?",Integer.class,run)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workspace_rpc_calls c JOIN workspace_rpc_grants g ON g.id=c.grant_id WHERE g.run_id=?",Integer.class,run)).isEqualTo(2);
    }
    @Test void closedOrExpiredGrantCannotStartModelInference() throws Exception {
        UUID run=newRun();String grant=ticket(run,"DECISION");
        grants.revoke(new WorkspaceRpcGrants.Ticket(grant));
        mockMvc.perform(post("/internal/v1/model-inference").header(AgentProtocol.INTERNAL_TOKEN_HEADER,TOKEN)
                .header(WorkspaceRpcGrants.HEADER,grant).contentType("application/json").content(body(run,"DECISION",10)))
                .andExpect(status().isForbidden());
        String expired=ticket(run,"DECISION");
        jdbc.update("UPDATE workspace_rpc_grants SET expires_at=now()-interval '1 second' WHERE run_id=?",run);
        mockMvc.perform(post("/internal/v1/model-inference").header(AgentProtocol.INTERNAL_TOKEN_HEADER,TOKEN)
                .header(WorkspaceRpcGrants.HEADER,expired).contentType("application/json").content(body(run,"DECISION",10)))
                .andExpect(status().isForbidden());
        assertThat(eventText(run)).doesNotContain("MODEL_INFERENCE");
    }
    @Test void closeDuringProviderCallRefusesLateOutputAndLeavesUnknownReceipt() {
        UUID run=newRun();String grant=ticket(run,"DECISION");
        var invocation=grants.admit(grant,run,"DECISION","a".repeat(64));
        grants.revoke(new WorkspaceRpcGrants.Ticket(grant));
        var events=new java.util.concurrent.atomic.AtomicInteger();
        org.assertj.core.api.Assertions.assertThatThrownBy(()->invocation.finish(true,events::incrementAndGet))
                .isInstanceOf(com.specagent.access.AccessDeniedException.class);
        invocation.unknown();
        assertThat(events.get()).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM workspace_rpc_calls WHERE request_hash=?",String.class,"a".repeat(64))).isEqualTo("UNKNOWN");
    }
    @Test void lostSingleExecutorSessionRefusesCallbackBeforeANewEpochIsIssued() throws Exception {
        UUID run=newRun();
        var lease=new com.specagent.agent.runtime.ExecutorLease(dataSource,"isolated-test");
        var provider=new org.springframework.beans.factory.ObjectProvider<com.specagent.agent.runtime.ExecutorLease>() {
            public com.specagent.agent.runtime.ExecutorLease getObject() {return lease;}
            public com.specagent.agent.runtime.ExecutorLease getObject(Object... args) {return lease;}
            public com.specagent.agent.runtime.ExecutorLease getIfAvailable() {return lease;}
            public com.specagent.agent.runtime.ExecutorLease getIfUnique() {return lease;}
        };
        var service=new com.specagent.agent.runtime.WorkspaceRpcGrantService(
                new org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate(jdbc),executionAuthorization,provider,transactions,sourceAuthorization);
        var grant=new java.util.concurrent.atomic.AtomicReference<WorkspaceRpcGrants.Ticket>();
        try {
            com.specagent.agent.runtime.WorkspaceExecutionContext.runWith(run,()->grant.set(
                    service.issue(run,"DECISION",1,java.time.Duration.ofSeconds(120))));
        } finally {lease.destroy();}
        org.assertj.core.api.Assertions.assertThatThrownBy(()->service.admit(grant.get().value(),run,"DECISION","b".repeat(64)))
                .isInstanceOf(com.specagent.access.AccessDeniedException.class);
    }

    @Test
    void rejectsNonExistentRunId() throws Exception {
        UUID fabricatedRunId = UUID.randomUUID();
        mockMvc.perform(post("/internal/v1/model-inference")
                        .header(AgentProtocol.INTERNAL_TOKEN_HEADER, TOKEN)
                        .contentType("application/json")
                        .content(body(fabricatedRunId, "STATE_UPDATE", 10)))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsUnknownCallTypeAndOversizedPrompt() throws Exception {
        UUID runId = newRun();
        mockMvc.perform(post("/internal/v1/model-inference")
                        .header(AgentProtocol.INTERNAL_TOKEN_HEADER, TOKEN)
                        .contentType("application/json")
                        .content(body(runId, "HACK", 10)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/internal/v1/model-inference")
                        .header(AgentProtocol.INTERNAL_TOKEN_HEADER, TOKEN)
                        .contentType("application/json")
                        .content(body(runId, "STATE_UPDATE", 300_000)))
                .andExpect(status().isBadRequest());
    }

    private UUID newRun() {
        Project project = projectService.createProject("Broker 测试项目");
        UUID run=runService.createQueuedDraftQuestion(project.id()).id();
        runService.claimDecisionCycleRun(run).orElseThrow();
        return run;
    }

    private String eventText(UUID runId) {
        StringBuilder text = new StringBuilder();
        eventRepository.findByRunId(runId)
                .forEach(event -> text.append(event.eventType()).append(' ')
                        .append(event.payload().toString()).append('\n'));
        return text.toString();
    }
}
