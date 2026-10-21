package com.specagent.agent.runtime;

import com.specagent.agent.protocol.*;
import com.specagent.common.Hashes;
import com.specagent.common.Maps;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.*;

/** JDBC persistence only. Callers supply authorization and the transaction boundary. */
@Repository
public class WaitDependencyRepository {
    private final NamedParameterJdbcTemplate jdbc;
    public WaitDependencyRepository(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    public void requireScope(AgentRequestEnvelope request) {
        var s = request.snapshot();
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM agent_runs r JOIN context_snapshots c ON c.id = r.context_snapshot_id
                  WHERE r.id = :run AND r.project_id = :project AND c.project_id = r.project_id
                    AND c.id = :snapshot AND c.context_hash = :hash
                    AND r.route_id IS NOT DISTINCT FROM CAST(:route AS uuid)
                    AND c.route_id IS NOT DISTINCT FROM r.route_id
                    AND c.tip_node_id IS NOT DISTINCT FROM CAST(:anchor AS uuid)
                    AND r.status IN ('created','running','context_built','model_called','reflected','persisted'))
                """, Maps.of("run", request.runId(), "project", s.projectId(),
                "snapshot", UUID.fromString(s.snapshotId()), "hash", s.contextHash(),
                "route", s.routeId(), "anchor", s.anchorNodeId()), Boolean.class)))
            throw new IllegalStateException("WAIT_SCOPE_MISMATCH");
    }

    public Optional<PendingDependencyProjection> frozen(UUID run) {
        return jdbc.query("SELECT payload, payload_hash FROM agent_run_dependency_projections WHERE run_id = :run",
                Map.of("run", run), (rs, n) -> {
                    String payload = rs.getString("payload");
                    if (!Hashes.sha256Hex(payload).equals(rs.getString("payload_hash")))
                        throw new IllegalStateException("WAIT_PROJECTION_CORRUPTED");
                    return AgentContracts.read(payload, PendingDependencyProjection.class);
                }).stream().findFirst();
    }

    public boolean freeze(PendingDependencyProjection projection) {
        String payload = AgentContracts.write(projection);
        if (payload.length() > 1_000_000) throw new IllegalStateException("WAIT_PROJECTION_TOO_LARGE");
        return jdbc.update("""
                INSERT INTO agent_run_dependency_projections(run_id, snapshot_id, payload, payload_hash)
                VALUES (:run, :snapshot, :payload, :hash) ON CONFLICT (run_id) DO NOTHING
                """, Map.of("run", projection.runId(), "snapshot", projection.snapshotId(),
                "payload", payload, "hash", Hashes.sha256Hex(payload))) == 1;
    }

    public List<PendingDependencyProjection.Dependency> candidates(AgentRequestEnvelope request) {
        var s = request.snapshot();
        return jdbc.query("""
                SELECT i.id, i.run_id, i.capability_id, i.pending_deadline
                  FROM capability_invocations i JOIN agent_runs owner ON owner.id = i.run_id
                 WHERE i.project_id = :project AND owner.project_id = i.project_id
                   AND i.run_id <> :run AND owner.status IN ('created','running','context_built','model_called','reflected','persisted','completed')
                   AND owner.route_id = :route AND owner.input_node_id IS NOT DISTINCT FROM CAST(:anchor AS uuid)
                   AND i.status = 'PENDING' AND i.pending_deadline > clock_timestamp()
                 ORDER BY i.created_at, i.id LIMIT 20
                """, Maps.of("project", s.projectId(), "route", s.routeId(),
                "anchor", s.anchorNodeId(), "run", request.runId()), (rs, n) ->
                new PendingDependencyProjection.Dependency(rs.getObject("id", UUID.class),
                        rs.getObject("run_id", UUID.class), rs.getString("capability_id"),
                        rs.getTimestamp("pending_deadline").toInstant().toString()));
    }

    /** Scope is checked again on replay and subscription, not inferred from the frozen view. */
    public void requireOwner(UUID invocation, PendingDependencyProjection projection, boolean lock) {
        var rows = jdbc.queryForList("""
                SELECT i.id FROM capability_invocations i JOIN agent_runs owner ON owner.id = i.run_id
                  JOIN routes route ON route.id = owner.route_id AND route.project_id = owner.project_id
                 WHERE i.id = :id AND i.project_id = :project AND owner.project_id = i.project_id
                   AND owner.route_id = :route AND owner.status IN ('created','running','context_built','model_called','reflected','persisted','completed')
                   AND owner.input_node_id IS NOT DISTINCT FROM CAST(:anchor AS uuid)
                   AND route.tip_node_id IS NOT DISTINCT FROM CAST(:anchor AS uuid)
                   AND route.lifecycle_status = 'open' AND i.run_id <> :run
                """ + (lock ? " FOR UPDATE OF i" : ""), Maps.of("id", invocation,
                "project", projection.projectId(), "route", projection.routeId(),
                "anchor", projection.anchorNodeId(), "run", projection.runId()));
        if (rows.size() != 1) throw new IllegalStateException("WAIT_DEPENDENCY_SCOPE_INVALID");
    }

    public Optional<UUID> resumedInvocation(UUID run) {
        return jdbc.query("""
                SELECT s.invocation_id FROM agent_runs child
                  JOIN agent_run_wait_subscriptions s ON s.wait_run_id = child.parent_run_id
                 WHERE child.id = :run AND s.project_id = child.project_id AND s.state = 'READY'
                """, Map.of("run", run), (rs, n) -> rs.getObject(1, UUID.class)).stream().findFirst();
    }

    public void subscribe(PendingDependencyProjection projection, UUID invocation, UUID proposal) {
        // requireOwner(..., true) has locked the invocation. A completion before this
        // transaction is read as READY; one after it must observe the inserted WAITING row.
        jdbc.update("""
                INSERT INTO agent_run_wait_subscriptions(wait_run_id, project_id, invocation_id, snapshot_id, proposal_id, state)
                SELECT :run, :project, id, :snapshot, :proposal,
                       CASE WHEN status = 'PENDING' THEN 'WAITING' ELSE 'READY' END
                  FROM capability_invocations WHERE id = :id AND project_id = :project
                   AND ((status = 'PENDING' AND pending_deadline > clock_timestamp()) OR status IN ('SUCCEEDED','FAILED'))
                ON CONFLICT (wait_run_id) DO NOTHING
                """, Map.of("run", projection.runId(), "project", projection.projectId(),
                "id", invocation, "snapshot", projection.snapshotId(), "proposal", proposal));
        if (!Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM agent_run_wait_subscriptions WHERE wait_run_id = :run
                  AND invocation_id = :id AND snapshot_id = :snapshot AND proposal_id = :proposal
                  AND project_id = :project AND state IN ('WAITING','READY'))
                """, Map.of("run", projection.runId(), "project", projection.projectId(),
                "id", invocation, "snapshot", projection.snapshotId(), "proposal", proposal), Boolean.class)))
            throw new IllegalStateException("WAIT_SUBSCRIPTION_REJECTED");
    }

    public Optional<String> state(UUID run) {
        // No result can resurrect a failed/stale owner or follow a moved route.
        jdbc.update("""
                UPDATE agent_run_wait_subscriptions s SET state = 'INVALIDATED'
                 WHERE s.wait_run_id = :run AND s.state IN ('WAITING','READY') AND NOT EXISTS (
                   SELECT 1 FROM agent_runs w JOIN routes route ON route.id = w.route_id
                     JOIN context_snapshots c ON c.id = s.snapshot_id
                     JOIN capability_invocations i ON i.id = s.invocation_id
                     JOIN agent_runs owner ON owner.id = i.run_id
                    WHERE w.id = s.wait_run_id AND w.project_id = s.project_id
                      AND i.project_id = s.project_id AND owner.project_id = s.project_id
                      AND route.project_id = s.project_id AND c.project_id = s.project_id
                      AND owner.status IN ('created','running','context_built','model_called','reflected','persisted','completed') AND w.status = 'completed'
                      AND owner.route_id = w.route_id AND c.route_id = w.route_id
                      AND owner.input_node_id IS NOT DISTINCT FROM c.tip_node_id
                      AND route.tip_node_id IS NOT DISTINCT FROM c.tip_node_id AND route.lifecycle_status = 'open')
                """, Map.of("run", run));
        return jdbc.query("SELECT state FROM agent_run_wait_subscriptions WHERE wait_run_id = :run",
                Map.of("run", run), (rs, n) -> rs.getString(1)).stream().findFirst();
    }

    public int invalidateDenied(UUID run) {
        return jdbc.update("""
                UPDATE agent_run_wait_subscriptions SET state = 'INVALIDATED'
                 WHERE wait_run_id = :run AND state IN ('WAITING','READY')
                   AND NOT EXISTS(SELECT 1 FROM agent_runs WHERE parent_run_id = :run)
                """, Map.of("run", run));
    }
}
