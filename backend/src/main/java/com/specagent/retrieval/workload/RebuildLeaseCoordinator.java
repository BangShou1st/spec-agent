package com.specagent.retrieval.workload;

import com.specagent.access.*;
import com.specagent.common.Maps;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** One short lease per bounded rebuild unit. Authorization scopes precede parent/generation/job locks. */
@Service
public class RebuildLeaseCoordinator {
    public record Token(UUID id,long epoch,UUID lease,UUID worker,UUID org,UUID corpus,String profile) {}
    private final NamedParameterJdbcTemplate jdbc;
    private final AuthorizationGuard authorization;
    private final TransactionTemplate transactions;
    private final UUID worker=UUID.randomUUID();
    private final int seconds;
    /** 本实例当前持有的租约:续期只针对这些行,且按 (id,epoch,lease,worker) 围栏。 */
    private final java.util.Map<UUID,Token> held=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ScheduledExecutorService heartbeat;
    private final int maxAttempts;
    private RebuildReadiness readiness;
    @org.springframework.beans.factory.annotation.Autowired
    public void setReadiness(RebuildReadiness readiness) { this.readiness=readiness; }
    public RebuildLeaseCoordinator(NamedParameterJdbcTemplate jdbc,AuthorizationGuard authorization,
            PlatformTransactionManager transactions,@Value("${spec.embedding-settings.rebuild-lease-seconds:120}") int seconds,
            @Value("${spec.embedding-settings.rebuild-max-attempts:3}") int maxAttempts,
            @Value("${spec.embedding-settings.rebuild-lease-heartbeat-ms:0}") int heartbeatMs) {
        if(seconds<100 || seconds>300) throw new IllegalArgumentException("Rebuild lease must be 100..300 seconds");
        if(maxAttempts<1 || maxAttempts>20) throw new IllegalArgumentException("Rebuild max attempts must be 1..20");
        this.jdbc=jdbc;this.authorization=authorization;this.transactions=new TransactionTemplate(transactions);this.seconds=seconds;
        this.maxAttempts=maxAttempts;
        this.readiness=new RebuildReadiness(jdbc);
        // 一次重建的合法最长耗时(split 30s + index 90s)与默认租约同量级:没有续租
        // 的话,一个只是慢但仍然存活的 worker 会丢租约并被重复执行。心跳只续本进程
        // 持有的租约;进程死亡则心跳停止,租约照常过期并被接管。
        int period=heartbeatMs>0?heartbeatMs:Math.max(10_000,seconds*1000/3);
        this.heartbeat=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{
            Thread t=new Thread(r,"rebuild-lease-heartbeat");t.setDaemon(true);return t;});
        this.heartbeat.scheduleAtFixedRate(this::renewHeldLeases,period,period,java.util.concurrent.TimeUnit.MILLISECONDS);
    }
    /** 续期:只延长本实例仍持有的、且身份完全一致的租约。 */
    private void renewHeldLeases() {
        for(var token:held.values()) {
            try {
                jdbc.update("UPDATE embedding_rebuilds SET lease_until=clock_timestamp()+make_interval(secs=>:seconds)"
                                + " WHERE id=:id AND state='RUNNING' AND execution_epoch=:epoch AND lease_id=:lease AND worker_id=:worker",
                        Maps.of("id",token.id(),"epoch",token.epoch(),"lease",token.lease(),"worker",token.worker(),"seconds",seconds));
            } catch (RuntimeException unavailable) {
                // 续期失败不改变所有权:租约会照常过期并被其他 worker 接管。
            }
        }
    }
    public Optional<Token> claim() {
        var ids=jdbc.queryForList("""
            SELECT id FROM embedding_rebuilds WHERE state='QUEUED' OR (state='RUNNING' AND lease_until<=clock_timestamp())
            ORDER BY created_at,id LIMIT 32
            """,Map.of(),UUID.class);
        return claimCandidates(ids);
    }
    /** Targeted claim uses the same protocol, for diagnostics and deterministic drivers. */
    public Optional<Token> claim(UUID id) {return claimCandidates(List.of(id));}
    private Optional<Token> claimCandidates(List<UUID> ids) {
        for(UUID id:ids) {
            var token=transactions.execute(tx->{
                var hint=hint(id);if(hint==null) return null;
                lockScopes(hint);
                var rows=jdbc.queryForList("SELECT * FROM embedding_rebuilds WHERE id=:id AND (state='QUEUED' OR (state='RUNNING' AND lease_until<=clock_timestamp())) FOR UPDATE SKIP LOCKED",Map.of("id",id));
                if(rows.isEmpty()) return null;
                var row=rows.getFirst();
                if(!active(row)) {failRow(row,"STALE_SCOPE_GRANT");return null;}
                invalidateChildren(id);
                long epoch=((Number)row.get("execution_epoch")).longValue()+1;UUID lease=UUID.randomUUID();
                jdbc.update("UPDATE embedding_rebuilds SET state='RUNNING',execution_epoch=:epoch,worker_id=:worker,lease_id=:lease,lease_until=clock_timestamp()+make_interval(secs=>:seconds),error_code=NULL WHERE id=:id",
                        Maps.of("id",id,"epoch",epoch,"worker",worker,"lease",lease,"seconds",seconds));
                return new Token(id,epoch,lease,worker,(UUID)row.get("org_id"),(UUID)row.get("corpus_id"),(String)row.get("profile_id"));
            });
            if(token!=null) { held.put(token.id(),token); return Optional.of(token); }
        }
        return Optional.empty();
    }
    public int recoverExpired() {
        var ids=jdbc.queryForList("SELECT id FROM embedding_rebuilds WHERE state='RUNNING' AND lease_until<=clock_timestamp() ORDER BY id LIMIT 32",Map.of(),UUID.class);
        int count=0;
        for(UUID id:ids) {
            Boolean changed=transactions.execute(tx->{
                var hint=hint(id);if(hint==null) return false;lockScopes(hint);
                var rows=jdbc.queryForList("SELECT * FROM embedding_rebuilds WHERE id=:id AND state='RUNNING' AND lease_until<=clock_timestamp() FOR UPDATE SKIP LOCKED",Map.of("id",id));
                if(rows.isEmpty()) return false;
                var row=rows.getFirst();invalidateChildren(id);
                if(!active(row)) failRow(row,"STALE_SCOPE_GRANT");
                else if(((Number)row.get("execution_epoch")).longValue()>=maxAttempts)
                    // 租约过期的重建不会无限重排队:超过最大尝试次数后按失败收口,
                    // 让失败可观察,而不是让同一份工作被无限重做。
                    failRow(row,"REBUILD_LEASE_EXPIRED");
                else jdbc.update("UPDATE embedding_rebuilds SET state='QUEUED',worker_id=NULL,lease_id=NULL,lease_until=NULL,error_code='REBUILD_LEASE_EXPIRED' WHERE id=:id",Map.of("id",id));
                return true;
            });
            if(Boolean.TRUE.equals(changed)) count++;
        }
        return count;
    }
    public void finish(Token token,Integer total,Integer processed,boolean ready) {
        held.remove(token.id());
        transactions.executeWithoutResult(tx->{
            var row=owned(token);if(!active(row)) throw stale();
            jdbc.update("UPDATE embedding_rebuilds SET state=:state,total=COALESCE(:total,total),processed=COALESCE(:processed,processed),worker_id=NULL,lease_id=NULL,lease_until=NULL WHERE id=:id",
                    Maps.of("id",token.id(),"state",ready?"READY":"QUEUED","total",total,"processed",processed));
            if(ready) readiness.record(token.id());
        });
    }
    public boolean fail(Token token,String code) {
        held.remove(token.id());
        return Boolean.TRUE.equals(transactions.execute(tx->{
            Map<String,Object> row;
            try {row=owned(token);}catch(IllegalStateException refused){return false;}
            // Only stale lease ownership is a no-op. Outcome storage failure must
            // escape so the business FAILED transition cannot commit without its fact.
            failRow(row,code);return true;
        }));
    }
    public Token capture(UUID corpus,UUID generation) {
        var token=RebuildLeaseContext.current();
        if(token!=null) {
            if(!corpus.equals(token.corpus())) throw stale();
            validateParent(token.id(),token.epoch(),token.lease(),corpus);return token;
        }
        if(generation!=null && Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM embedding_rebuilds WHERE id=:id AND state IN ('QUEUED','RUNNING'))",Map.of("id",generation),Boolean.class))) throw stale();
        return null;
    }
    public void validateGrant(Map<String,Object> row) {
        if(row.get("rebuild_id")!=null) validateParent((UUID)row.get("rebuild_id"),((Number)row.get("rebuild_epoch")).longValue(),(UUID)row.get("rebuild_lease_id"),(UUID)row.get("corpus_id"));
    }
    public void validateForJob(UUID job) {
        var rows=jdbc.queryForList("SELECT corpus_id,rebuild_id,rebuild_epoch,rebuild_lease_id FROM retrieval_scope_grants WHERE workload_kind='INDEX_JOB' AND workload_id=:id",Map.of("id",job));
        if(rows.size()!=1) throw stale();validateGrant(rows.getFirst());
    }
    private void validateParent(UUID id,long epoch,UUID lease,UUID corpus) {
        transactions.executeWithoutResult(tx->{
            var hint=hint(id);if(hint==null) throw stale();lockScopes(hint);
            var rows=jdbc.queryForList("SELECT * FROM embedding_rebuilds WHERE id=:id AND state='RUNNING' AND execution_epoch=:epoch AND lease_id=:lease AND lease_until>clock_timestamp() FOR SHARE",Maps.of("id",id,"epoch",epoch,"lease",lease));
            if(rows.size()!=1 || !active(rows.getFirst()) || !corpus.equals(rows.getFirst().get("corpus_id"))) throw stale();
        });
    }
    private Map<String,Object> owned(Token token) {
        var hint=hint(token.id());if(hint==null) throw stale();lockScopes(hint);
        var rows=jdbc.queryForList("SELECT * FROM embedding_rebuilds WHERE id=:id AND state='RUNNING' AND execution_epoch=:epoch AND lease_id=:lease AND worker_id=:worker AND lease_until>clock_timestamp() FOR UPDATE",
                Maps.of("id",token.id(),"epoch",token.epoch(),"lease",token.lease(),"worker",token.worker()));
        if(rows.size()!=1 || !token.org().equals(rows.getFirst().get("org_id")) || !token.corpus().equals(rows.getFirst().get("corpus_id"))
                || !token.profile().equals(rows.getFirst().get("profile_id"))) throw stale();return rows.getFirst();
    }
    private Map<String,Object> hint(UUID id) {
        var rows=jdbc.queryForList("SELECT org_id,project_id FROM embedding_rebuilds WHERE id=:id",Map.of("id",id));return rows.isEmpty()?null:rows.getFirst();
    }
    private void lockScopes(Map<String,Object> row) {
        var keys=new ArrayList<>(List.of(AuthorizationScopes.org((UUID)row.get("org_id"))));
        if(row.get("project_id")!=null) keys.add(AuthorizationScopes.project((UUID)row.get("project_id")));
        authorization.lockSharedFailClosed(keys);
    }
    private boolean active(Map<String,Object> row) {
        UUID org=(UUID)row.get("org_id"),project=(UUID)row.get("project_id");
        return authorization.canUseOrg(ActorContext.system(org),org,null).allowed()
                && (project==null || authorization.resources().projectOrg(project).filter(org::equals).isPresent());
    }
    private void invalidateChildren(UUID id) {
        jdbc.update("UPDATE retrieval_index_jobs SET state='STALE' WHERE state='CLAIMED' AND id IN (SELECT workload_id FROM retrieval_scope_grants WHERE rebuild_id=:id)",Map.of("id",id));
        jdbc.update("UPDATE retrieval_scope_grants SET revoked=TRUE WHERE rebuild_id=:id",Map.of("id",id));
    }
    private void failRow(Map<String,Object> row,String code) {
        UUID id=(UUID)row.get("id");invalidateChildren(id);
        jdbc.update("UPDATE embedding_rebuilds SET state='FAILED',error_code=:code,worker_id=NULL,lease_id=NULL,lease_until=NULL WHERE id=:id",Maps.of("id",id,"code",code));
        jdbc.update("UPDATE retrieval_index_generations SET state='FAILED' WHERE id=:id AND state='PREPARING'",Map.of("id",id));
        readiness.record(id);
    }
    private static IllegalStateException stale() {return new IllegalStateException("STALE_WORKLOAD");}
}
