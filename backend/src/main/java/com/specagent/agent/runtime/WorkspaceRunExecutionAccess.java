package com.specagent.agent.runtime;

import com.specagent.access.*;
import com.specagent.runtime.execution.*;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class WorkspaceRunExecutionAccess implements WorkspaceRunExecutionAccessPort {
    private final ExecutionFence fence;
    private final WorkspaceExecutionAuthorization authorization;
    private final AuthorizationGuard guard;
    private final NamedParameterJdbcTemplate jdbc;

    public WorkspaceRunExecutionAccess(ExecutionFence fence, WorkspaceExecutionAuthorization authorization,
            AuthorizationGuard guard, NamedParameterJdbcTemplate jdbc) {
        this.fence=fence;this.authorization=authorization;this.guard=guard;this.jdbc=jdbc;
    }

    @Override
    public void requireExecution(UUID project, UUID run) {
        if (!TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("Run access requires a transaction");
        if (project==null || run==null) throw AccessDeniedException.notVisible("Run not found");
        UUID bound=WorkspaceExecutionContext.currentRun();
        if (bound!=null && !bound.equals(run)) throw new TaskOwnershipLostException("Run differs from bound execution");
        var caller=guard.ownershipActor();
        fence.lockOwnershipForWrite();
        var persisted=authorization.authorize(run);
        if (!caller.equals(persisted)) throw AccessDeniedException.notVisible("Run not found");
        var rows=jdbc.queryForList("SELECT project_id,status FROM agent_runs WHERE id=:run FOR SHARE",Map.of("run",run));
        if (rows.size()!=1 || !project.equals(rows.getFirst().get("project_id")))
            throw AccessDeniedException.notVisible("Run not found");
        AgentRunStatus status=AgentRunStatus.fromCode((String)rows.getFirst().get("status"));
        if (status==AgentRunStatus.CREATED || status.isTerminal())
            throw new TaskOwnershipLostException("Run is not executing");
    }
}
