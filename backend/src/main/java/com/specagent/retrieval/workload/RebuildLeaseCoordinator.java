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
    public RebuildLeaseCoordinator(NamedParameterJdbcTemplate jdbc,AuthorizationGuard authorization,
            PlatformTransactionManager transactions,@Value("${spec.embedding-settings.rebuild-lease-seconds:120}") int seconds) {
        if(seconds<100 || seconds>300) throw new IllegalArgumentException("Rebuild lease must be 100..300 seconds");
        this.jdbc=jdbc;this.authorization=authorization;this.transactions=new TransactionTemplate(transactions);this.seconds=seconds;
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
            if(token!=null) return Optional.of(token);
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
                else jdbc.update("UPDATE embedding_rebuilds SET state='QUEUED',worker_id=NULL,lease_id=NULL,lease_until=NULL,error_code='REBUILD_LEASE_EXPIRED' WHERE id=:id",Map.of("id",id));
                return true;
            });
            if(Boolean.TRUE.equals(changed)) count++;
        }
        return count;
    }
    public void finish(Token token,Integer total,Integer processed,boolean ready) {
        transactions.executeWithoutResult(tx->{
            var row=owned(token);if(!active(row)) throw stale();
            jdbc.update("UPDATE embedding_rebuilds SET state=:state,total=COALESCE(:total,total),processed=COALESCE(:processed,processed),worker_id=NULL,lease_id=NULL,lease_until=NULL WHERE id=:id",
                    Maps.of("id",token.id(),"state",ready?"READY":"QUEUED","total",total,"processed",processed));
        });
    }
    public boolean fail(Token token,String code) {
        return Boolean.TRUE.equals(transactions.execute(tx->{
            try {var row=owned(token);failRow(row,code);return true;}catch(IllegalStateException refused){return false;}
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
    }
    private static IllegalStateException stale() {return new IllegalStateException("STALE_WORKLOAD");}
}
