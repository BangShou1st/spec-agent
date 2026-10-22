package com.specagent.agent.runtime;

import com.specagent.agent.protocol.*;
import com.specagent.common.Hashes;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import java.nio.file.*;
import java.sql.ResultSet;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** SQL submission and mapper integrity only; no PostgreSQL transaction/concurrency claim. */
class WaitDependencyRepositoryOfflineTest {
    final NamedParameterJdbcTemplate jdbc=mock(NamedParameterJdbcTemplate.class);
    final WaitDependencyRepository repository=new WaitDependencyRepository(jdbc);
    PendingDependencyProjection projection() throws Exception {
        return AgentContracts.read(Files.readString(Path.of("../contracts/fixtures/agent-input-v4-wait.json")),
                AgentRequestEnvelope.class).pendingDependencies();
    }
    @Test void subscriptionHandlesCompletedBeforeSubscribeAndRefusesReplacement() throws Exception {
        var p=projection();var id=p.dependencies().getFirst().invocationId();var proposal=UUID.randomUUID();
        when(jdbc.queryForObject(anyString(),anyMap(),eq(Boolean.class))).thenReturn(true,false);
        repository.subscribe(p,id,proposal);
        verify(jdbc).update(argThat(sql->sql.contains("CASE WHEN status = 'PENDING' THEN 'WAITING' ELSE 'READY' END")
                && sql.contains("status IN ('SUCCEEDED','FAILED')") && sql.contains("ON CONFLICT (wait_run_id) DO NOTHING")
                && sql.contains("pending_deadline > clock_timestamp()")),anyMap());
        assertThatThrownBy(()->repository.subscribe(p,UUID.randomUUID(),proposal)).hasMessage("WAIT_SUBSCRIPTION_REJECTED");
    }
    @Test void invocationLockAndScopeIncludeRouteAnchorAndOwnerProject() throws Exception {
        var p=projection();when(jdbc.queryForList(anyString(),anyMap())).thenReturn(List.of(Map.of("id",UUID.randomUUID())));
        repository.requireOwner(p.dependencies().getFirst().invocationId(),p,true);
        verify(jdbc).queryForList(argThat(sql->sql.contains("FOR UPDATE OF i")&&sql.contains("owner.project_id = i.project_id")
                &&sql.contains("route.tip_node_id IS NOT DISTINCT FROM")&&sql.contains("owner.status IN ('created','running','context_built','model_called','reflected','persisted','completed')")),anyMap());
    }
    @Test void completionSettlesMatchingSubscribersAndOutboxesAllTargetsWithoutTerminalSnapshotFilter() {
        new ContinuationCheckRepository(jdbc).completed(UUID.randomUUID());
        verify(jdbc).update(argThat(sql->sql.contains("UPDATE agent_run_wait_subscriptions s SET state = 'READY'")
                &&sql.contains("s.project_id = i.project_id")&&sql.contains("w.project_id = s.project_id")
                &&sql.contains("SELECT wait_run_id FROM settled")&&!sql.contains("r.status")&&!sql.contains("w.status")),anyMap());
    }
    @Test void corruptedFrozenPayloadNeverFallsBackToLiveProjection() throws Exception {
        when(jdbc.query(anyString(),anyMap(),org.mockito.ArgumentMatchers.<RowMapper<PendingDependencyProjection>>any()))
                .thenAnswer(call->{RowMapper<PendingDependencyProjection> mapper=call.getArgument(2);var rs=mock(ResultSet.class);
                    when(rs.getString("payload")).thenReturn("{}");when(rs.getString("payload_hash")).thenReturn("a".repeat(64));
                    return List.of(mapper.mapRow(rs,0));});
        assertThatThrownBy(()->repository.frozen(UUID.randomUUID())).hasMessage("WAIT_PROJECTION_CORRUPTED");
        verify(jdbc,never()).update(anyString(),anyMap());
    }
    @Test void oversizedSettledObservationCannotPersistAnUnboundedEnvelope() throws Exception {
        var p=projection();
        var huge=new CapabilityResultView(UUID.randomUUID().toString(),"deferred.probe","SUCCEEDED",
                Map.of("text","x".repeat(1_000_000)),List.of(),Map.of());
        var invalid=new PendingDependencyProjection(p.version(),p.runId(),p.snapshotId(),p.contextHash(),p.projectId(),
                p.routeId(),p.anchorNodeId(),List.of(),List.of(huge));
        assertThatThrownBy(()->repository.freeze(invalid)).hasMessage("WAIT_PROJECTION_TOO_LARGE");
        verifyNoInteractions(jdbc);
    }

    @Test void staleRouteAndFailedOwnerInvalidateSubscriptionBeforeStateRead() {
        repository.state(UUID.randomUUID());
        var order=inOrder(jdbc);
        order.verify(jdbc).update(argThat(sql->sql.contains("SET state = 'INVALIDATED'")&&sql.contains("owner.status IN ('created','running','context_built','model_called','reflected','persisted','completed')")
                &&sql.contains("route.tip_node_id IS NOT DISTINCT FROM c.tip_node_id")),anyMap());
        order.verify(jdbc).query(anyString(),anyMap(),org.mockito.ArgumentMatchers.<RowMapper<String>>any());
    }
}
