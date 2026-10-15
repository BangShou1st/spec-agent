package com.specagent.connection;

import com.specagent.access.*;
import com.specagent.access.identity.IdentityRepository;
import com.specagent.capability.*;
import com.specagent.mcp.McpDiscoveryCacheRepository;
import com.specagent.mcp.domain.McpTool;
import com.specagent.mcp.transport.McpTransportException;
import com.specagent.agent.broker.WorkspaceRpcGrants;
import com.specagent.agent.protocol.AgentInputSnapshot;
import com.specagent.agent.runtime.*;
import com.specagent.agent.snapshot.*;
import com.specagent.workspace.context.*;
import com.specagent.workspace.project.ProjectService;
import com.specagent.common.Hashes;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class McpRecordedSourceIntegrationTest {
    @Autowired IdentityRepository identities;
    @Autowired ConnectionLifecycleService lifecycle;
    @Autowired ConnectionRepository connections;
    @Autowired RecordedMcpSourcePort policy;
    @Autowired CapabilityInvocationRepository invocations;
    @Autowired CapabilityRuntime runtime;
    @Autowired McpDiscoveryCacheRepository cache;
    @Autowired ProjectService projects;
    @Autowired RunService runs;
    @Autowired ContextBuilder contexts;
    @Autowired AgentInputSnapshotBuilder snapshots;
    @Autowired WorkspaceRpcGrants grants;
    @Autowired JdbcTemplate jdbc;
    private record Input(ActorContext actor,Connection connection,UUID run,ContextSnapshot context) {}
    private <T> T bound(Input f,Supplier<T> action) {
        return as(f.actor(),()->{AtomicReference<T> result=new AtomicReference<>();
            WorkspaceExecutionContext.runWith(f.run(),()->result.set(action.get()));return result.get();});
    }
    private Input input() {
        var actor=actor(org());var c=connection(actor,"USER");
        return as(actor,()-> {
            UUID project=projects.createProject("mcp-source-input").id();
            UUID run=runs.createQueuedDraftQuestion(project).id();runs.claimDecisionCycleRun(run).orElseThrow();
            UUID id=UUID.randomUUID();String key="mcp-input-"+id;
            invocations.claim(new CapabilityInvocation(id,key,capability(c),project,run,Map.of()));
            invocations.complete(id,new CapabilityResult(id,key,capability(c),CapabilityResult.Status.SUCCEEDED,
                    Map.of("private","owner-result"),List.of(),proof(c),List.of()));
            return new Input(actor,c,run,contexts.buildFromActiveRoute(project,run,ContextOperationType.INITIAL));
        });
    }
    private AgentInputSnapshot snapshot(Input f) {return bound(f,()->snapshots.build(f.context()));}
    private void disable(Input f) {as(f.actor(),()->{connections.updateEnabled(f.connection().id(),false);return null;});}
    private WorkspaceRpcGrants.Ticket ticket(Input f,AgentInputSnapshot snapshot) {
        return bound(f,()->grants.issue(f.run(),"DECISION",2,Duration.ofSeconds(120),snapshot));
    }
    private <T> T as(ActorContext actor,Supplier<T> action) {try(var scope=ActorContextHolder.bind(actor)){return action.get();}}
    private UUID org() {UUID id=UUID.randomUUID();identities.insertOrganizationIfAbsent(id,"mcp-recorded","STANDARD");return id;}
    private ActorContext actor(UUID org) {
        UUID user=UUID.randomUUID();identities.insertUserIfAbsent(user,"mcp-result-owner");identities.upsertMembership(org,user,OrgRole.OWNER);
        return ActorContext.oidc(user,org);
    }
    private Connection connection(ActorContext actor,String ownership) {
        return as(actor,()-> {
            var c=lifecycle.createOwned(ConnectionKind.CUSTOM_MCP,"result-source",Map.of("serverUrl","https://mcp.example.test/service"),null,ownership);
            connections.updateStatus(c.id(),ConnectionStatus.CONNECTED,null);connections.updateEnabled(c.id(),true);
            return connections.findById(c.id()).orElseThrow();
        });
    }
    private String capability(Connection c) {return "mcp."+c.connectionId()+".read";}
    private Map<String,Object> proof(Connection c) {
        return Map.of("kind","MCP_TOOL","connectionRowId",c.id().toString(),"connectionId",c.connectionId(),
                "configurationVersion",c.configurationVersion(),"toolName","read");
    }
    private RecordedMcpSourcePort.Reference ref(Connection c) {return policy.reference(capability(c),proof(c));}
    private String recorded(Connection c,ActorContext actor) {
        String key="mcp-source-record-"+UUID.randomUUID();UUID id=UUID.randomUUID();
        as(actor,()-> {
            cache.upsert(c.id(),c.configurationVersion(),List.of(new McpTool("read","read",Map.of(),Map.of("readOnlyHint",true))),List.of(),List.of(),"test");
            invocations.claim(new CapabilityInvocation(id,key,capability(c),null,null,Map.of()));
            invocations.complete(id,new CapabilityResult(id,key,capability(c),CapabilityResult.Status.SUCCEEDED,
                    Map.of("private","owner-result"),List.of(),proof(c),List.of()));return null;
        });return key;
    }
    @Test void sameOwnerCanUseCurrentVerifiedSource() {
        var actor=actor(org());var c=connection(actor,"USER");
        assertThatCode(()->as(actor,()->{policy.require(ref(c));return null;})).doesNotThrowAnyException();
    }
    @Test void sameOrganizationAdminCannotUseAnotherUsersPersonalResult() {
        UUID org=org();var owner=actor(org);var admin=actor(org);var c=connection(owner,"USER");
        assertThat(as(admin,()->policy.visible(capability(c),proof(c)))).isFalse();
    }
    @Test void otherOrganizationCannotUseEvenAnOrganizationOwnedResult() {
        var owner=actor(org());var other=actor(org());var c=connection(owner,"ORG");
        assertThat(as(other,()->policy.visible(capability(c),proof(c)))).isFalse();
    }
    @Test void disabledConnectionInvalidatesStoredResultSource() {
        var actor=actor(org());var c=connection(actor,"USER");
        as(actor,()->{connections.updateEnabled(c.id(),false);return null;});
        assertThat(as(actor,()->policy.visible(capability(c),proof(c)))).isFalse();
    }
    @Test void disableAndReenableDoesNotReviveOldVersion() {
        var actor=actor(org());var c=connection(actor,"USER");
        as(actor,()->{connections.updateEnabled(c.id(),false);connections.updateEnabled(c.id(),true);return null;});
        assertThat(as(actor,()->policy.visible(capability(c),proof(c)))).isFalse();
    }
    @Test void legacyResultWithoutVersionEvidenceIsNotAuthorizedByCurrentConfiguration() {
        var actor=actor(org());var c=connection(actor,"USER");
        assertThat(as(actor,()->policy.visible(capability(c),Map.of("kind","MCP_TOOL","connectionId",c.connectionId(),"toolName","read")))).isFalse();
    }
    @Test void validCachedReplayRequiresSourceButDoesNotCallServer() {
        var actor=actor(org());var c=connection(actor,"USER");var key=recorded(c,actor);
        var result=as(actor,()->runtime.invokeApplicationScoped(key,capability(c),null,Map.of()));
        assertThat(result.status()).isEqualTo(CapabilityResult.Status.REPLAYED);
        assertThat(result.content()).containsEntry("private","owner-result");
    }
    @Test void sourceDisabledBeforeReplayCannotReturnOldPrivateBodyEvenWhenAdapterUnavailable() {
        var actor=actor(org());var c=connection(actor,"USER");var key=recorded(c,actor);
        as(actor,()->{connections.updateEnabled(c.id(),false);return null;});
        assertThatThrownBy(()->as(actor,()->runtime.invokeApplicationScoped(key,capability(c),null,Map.of())))
                .isInstanceOf(McpTransportException.class);
    }
    @Test void personalResultReplayCannotBeBorrowedByAnotherMemberKnowingItsKey() {
        UUID org=org();var owner=actor(org);var other=actor(org);var c=connection(owner,"USER");var key=recorded(c,owner);
        assertThatThrownBy(()->as(other,()->runtime.invokeApplicationScoped(key,capability(c),null,Map.of())))
                .isInstanceOf(McpTransportException.class);
    }
    @Test void freshInputOmitsRevokedMcpResultBeforeFreezing() {
        var f=input();disable(f);
        assertThat(snapshot(f).capabilityResults()).noneMatch(r->r.capabilityId().equals(capability(f.connection())));
    }
    @Test void frozenInputCannotReplayBodyAfterConnectionRevocation() {
        var f=input();var original=snapshot(f);
        assertThat(original.capabilityResults()).anyMatch(r->r.content().containsValue("owner-result"));
        disable(f);
        assertThatThrownBy(()->snapshot(f)).isInstanceOf(FrozenSourceAuthorizationRefusedException.class);
    }
    @Test void issuedMcpTicketPinsSourceAndRefusesRevocationWithoutSpendingBudget() {
        var f=input();var ticket=ticket(f,snapshot(f));String hash=Hashes.sha256Hex(ticket.value());
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(source_mcp_bindings) FROM workspace_rpc_grants WHERE token_hash=?",Integer.class,hash)).isEqualTo(1);
        disable(f);
        assertThatThrownBy(()->grants.admit(ticket.value(),f.run(),"DECISION","b".repeat(64))).isInstanceOf(AccessDeniedException.class);
        assertThat(jdbc.queryForObject("SELECT consumed_calls FROM workspace_rpc_grants WHERE token_hash=?",Integer.class,hash)).isZero();
    }
    @Test void mcpRevocationDuringModelCallRefusesLateCommit() {
        var f=input();var ticket=ticket(f,snapshot(f));
        var invocation=grants.admit(ticket.value(),f.run(),"DECISION","c".repeat(64));disable(f);
        AtomicBoolean committed=new AtomicBoolean();
        assertThatThrownBy(()->invocation.finish(true,()->committed.set(true))).isInstanceOf(AccessDeniedException.class);
        assertThat(committed.get()).isFalse();invocation.unknown();
        assertThat(jdbc.queryForObject("SELECT c.status FROM workspace_rpc_calls c JOIN workspace_rpc_grants g ON g.id=c.grant_id WHERE g.token_hash=?",String.class,Hashes.sha256Hex(ticket.value()))).isEqualTo("UNKNOWN");
    }
}
