package com.specagent.agent.runtime;

import com.specagent.access.*;
import com.specagent.agent.broker.WorkspaceRpcGrants;
import com.specagent.agent.protocol.AgentProtocol;
import com.specagent.common.Hashes;
import com.specagent.common.Maps;
import com.specagent.runtime.execution.*;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Random capability per host Brain invocation; no provider secrets or model responses are stored. */
@Service
public class WorkspaceRpcGrantService implements WorkspaceRpcGrants {
    private final NamedParameterJdbcTemplate jdbc;
    private final WorkspaceExecutionAuthorization authorization;
    private final ObjectProvider<ExecutorLease> leases;
    private final TransactionTemplate transactions;
    private final WorkspaceRpcSourceAuthorization sources;
    public WorkspaceRpcGrantService(NamedParameterJdbcTemplate jdbc,WorkspaceExecutionAuthorization authorization,
            ObjectProvider<ExecutorLease> leases,PlatformTransactionManager transactions,WorkspaceRpcSourceAuthorization sources) {
        this.jdbc=jdbc;this.authorization=authorization;this.leases=leases;
        this.transactions=new TransactionTemplate(transactions);
        this.sources=sources;
    }
    @Override public Ticket issue(UUID run,String type,int maximum,Duration lifetime) {
        return issue(run,type,maximum,lifetime,null);
    }
    @Override public Ticket issue(UUID run,String type,int maximum,Duration lifetime,
            com.specagent.agent.protocol.AgentInputSnapshot snapshot) {
        if(!run.equals(WorkspaceExecutionContext.currentRun())) throw refused();
        if(!AgentProtocol.CALL_TYPES.contains(type) || maximum<1 || maximum>2
                || lifetime==null || lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(Duration.ofSeconds(180))>0)
            throw new IllegalArgumentException("Invalid workspace RPC grant");
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);
        String raw=Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        var token=ExecutionTokenContext.current().orElse(null);
        var lease=leases.getIfAvailable();
        long epoch=token!=null?0:lease==null?0:lease.epoch();
        Integer pid=token!=null || lease==null?null:lease.getLeasePid();
        transactions.executeWithoutResult(tx->{
            if(lease!=null && token==null) lease.assertOwned();
            var actor=authorization.authorize(run);
            lockRun(run);
            var binding=sources.capture(run,actor,snapshot);
            jdbc.update("""
                INSERT INTO workspace_rpc_grants(id,token_hash,run_id,actor_user_id,actor_org_id,actor_source,
                    audience,purpose,call_type,maximum_calls,task_id,task_epoch,task_lease_id,task_worker_id,
                    executor_epoch,executor_pid,expires_at,source_snapshot_id,source_payload_hash,source_skill_version_ids,source_mcp_bindings)
                VALUES(:id,:hash,:run,:user,:org,:source,'workspace-brain.v1','MODEL_INFERENCE',:type,:maximum,
                    :task,:epoch,:lease,:worker,:executor,:pid,:expires,:snapshot,:payloadHash,
                    ARRAY(SELECT jsonb_array_elements_text(CAST(:skills AS jsonb))::uuid),CAST(:mcp AS jsonb))
                """,Maps.of("id",UUID.randomUUID(),"hash",Hashes.sha256Hex(raw),"run",run,"user",actor.userId(),
                    "org",actor.orgId(),"source",actor.source().name(),"type",type,"maximum",maximum,
                    "task",token==null?null:token.taskId(),"epoch",token==null?null:token.epoch(),
                    "lease",token==null?null:token.leaseId(),"worker",token==null?null:token.workerId(),
                    "executor",epoch,"pid",pid,"expires",Timestamp.from(Instant.now().plus(lifetime)),
                    "snapshot",binding.snapshotId(),"payloadHash",binding.payloadHash(),
                    "skills",com.specagent.agent.protocol.AgentContracts.write(binding.skills()),
                    "mcp",com.specagent.agent.protocol.AgentContracts.write(binding.mcp())));
        });
        return new Ticket(raw);
    }
    @Override public void revoke(Ticket ticket) {
        jdbc.update("UPDATE workspace_rpc_grants SET revoked=TRUE WHERE token_hash=:hash",
                Map.of("hash",Hashes.sha256Hex(ticket.value())));
    }
    @Override public Invocation admit(String raw,UUID run,String type,String hash) {
        if(raw==null || !raw.matches("[A-Za-z0-9_-]{43}") || hash==null || !hash.matches("[a-f0-9]{64}")) throw refused();
        var hints=jdbc.queryForList("SELECT * FROM workspace_rpc_grants WHERE token_hash=:hash",
                Map.of("hash",Hashes.sha256Hex(raw)));
        if(hints.size()!=1) throw refused();
        Map<String,Object> hint=hints.getFirst();
        if(!run.equals(hint.get("run_id")) || !type.equals(hint.get("call_type"))) throw refused();
        UUID id=(UUID)hint.get("id");
        ActorContext actor=withToken(hint,()->transactions.execute(tx->{
            var subject=validate(hint);
            if(jdbc.queryForObject("SELECT count(*) FROM workspace_rpc_calls WHERE grant_id=:id AND request_hash=:hash",
                    Maps.of("id",id,"hash",hash),Integer.class)!=0) throw refused();
            if(jdbc.update("UPDATE workspace_rpc_grants SET consumed_calls=consumed_calls+1 WHERE id=:id AND consumed_calls<maximum_calls",
                    Map.of("id",id))!=1) throw refused();
            jdbc.update("INSERT INTO workspace_rpc_calls(grant_id,request_hash,status) VALUES(:id,:hash,'RESERVED')",Maps.of("id",id,"hash",hash));
            return subject;
        }));
        return new Invocation() {
            public ActorContext actor() {return actor;}
            public void finish(boolean success,Runnable event) {
                withToken(hint,()->transactions.execute(tx->{
                    validate(hint);
                    if(jdbc.update("UPDATE workspace_rpc_calls SET status=:state,completed_at=now() WHERE grant_id=:id AND request_hash=:hash AND status='RESERVED'",
                            Maps.of("id",id,"hash",hash,"state",success?"SUCCEEDED":"UNKNOWN"))!=1) throw refused();
                    event.run();return null;
                }));
            }
            public void unknown() {
                jdbc.update("UPDATE workspace_rpc_calls SET status='UNKNOWN',completed_at=now() WHERE grant_id=:id AND request_hash=:hash AND status='RESERVED'",
                        Maps.of("id",id,"hash",hash));
            }
        };
    }
    private ActorContext validate(Map<String,Object> hint) {
        long epoch=((Number)hint.get("executor_epoch")).longValue();
        if(epoch>0) {
            Long current=jdbc.queryForObject("SELECT epoch FROM executor_ownership WHERE id=1 FOR SHARE",Map.of(),Long.class);
            if(current==null || current!=epoch) throw refused();
            if(!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND granted AND mode='ExclusiveLock'
                    AND pid=:pid AND classid=CAST(:class AS oid) AND objid=CAST(:object AS oid) AND objsubid=1
                    AND database=(SELECT oid FROM pg_database WHERE datname=current_database()))
                """,Maps.of("pid",hint.get("executor_pid"),"class",ExecutorLease.LOCK_KEY>>>32,
                    "object",ExecutorLease.LOCK_KEY&0xffffffffL),Boolean.class))) throw refused();
        }
        UUID run=(UUID)hint.get("run_id");
        var actor=authorization.authorize(run);
        if(!actor.userId().equals(hint.get("actor_user_id")) || !actor.orgId().equals(hint.get("actor_org_id"))
                || !actor.source().name().equals(hint.get("actor_source"))) throw refused();
        lockRun(run);
        var rows=jdbc.queryForList("SELECT * FROM workspace_rpc_grants WHERE id=:id AND NOT revoked AND expires_at>clock_timestamp() FOR UPDATE",
                Map.of("id",hint.get("id")));
        if(rows.size()!=1) throw refused();
        Map<String,Object> live=rows.getFirst();
        List<UUID> skills;
        try {
            Object[] values=(Object[])((java.sql.Array)live.get("source_skill_version_ids")).getArray();
            skills=Arrays.stream(values).map(value -> value instanceof UUID id ? id : UUID.fromString(value.toString())).toList();
        } catch(Exception ex) {throw refused();}
        sources.verify(run,actor,new WorkspaceRpcSourceAuthorization.Binding(
                (UUID)live.get("source_snapshot_id"),(String)live.get("source_payload_hash"),skills,
                Arrays.asList(com.specagent.agent.protocol.AgentContracts.read(live.get("source_mcp_bindings").toString(),
                        com.specagent.capability.RecordedMcpSourcePort.Reference[].class))));
        return actor;
    }
    private void lockRun(UUID run) {
        var rows=jdbc.queryForList("SELECT status FROM agent_runs WHERE id=:run FOR UPDATE",Map.of("run",run));
        if(rows.size()!=1 || Set.of("created","completed","failed").contains(rows.getFirst().get("status"))) throw refused();
    }
    private <T> T withToken(Map<String,Object> hint,Supplier<T> action) {
        if(hint.get("task_id")==null) {
            try{return action.get();}catch(TaskOwnershipLostException lost){throw refused();}
        }
        var previous=ExecutionTokenContext.current().orElse(null);
        ExecutionTokenContext.bind(new ExecutionToken((UUID)hint.get("task_id"),((Number)hint.get("task_epoch")).longValue(),
                (UUID)hint.get("task_lease_id"),(UUID)hint.get("task_worker_id"),RuntimeTaskFamily.WORKSPACE,(UUID)hint.get("run_id")));
        try{return action.get();}catch(TaskOwnershipLostException lost){throw refused();}
        finally{if(previous==null) ExecutionTokenContext.clear();else ExecutionTokenContext.bind(previous);}
    }
    private static AccessDeniedException refused() {
        return AccessDeniedException.forbidden(AccessReason.UNAUTHENTICATED,"Workspace RPC grant is absent, stale or refused");
    }
}
