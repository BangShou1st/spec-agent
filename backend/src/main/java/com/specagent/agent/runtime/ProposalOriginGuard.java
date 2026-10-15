package com.specagent.agent.runtime;

import com.specagent.runtime.execution.RuntimeTaskRepository;
import com.specagent.runtime.execution.RuntimeScopeMutex;
import com.specagent.runtime.execution.RuntimeExecutionProtocol;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** The acceptance transaction locks task -> authorization scopes -> origin Run -> business rows. */
@Component
public class ProposalOriginGuard {
    private final RuntimeTaskRepository tasks;
    private final AgentRunRepository runs;
    private final RuntimeScopeMutex scopeMutex;
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc;

    public ProposalOriginGuard(RuntimeTaskRepository tasks, AgentRunRepository runs, RuntimeScopeMutex scopeMutex,
            org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc) {
        this.tasks = tasks;
        this.runs = runs;
        this.scopeMutex = scopeMutex;
        this.jdbc = jdbc;
    }

    public void lockTaskBeforeAuthorization(UUID runId) {
        if (runId == null) return;
        tasks.lockWorkspaceRunTask(runId).ifPresent(task -> {
            if (!task.state().isTerminal()) throw new ProposalExecutionConflictException();
        });
    }

    public Optional<AgentRun> lockOriginAfterAuthorization(UUID runId, UUID projectId) {
        if (runId == null) return Optional.empty();
        Optional<AgentRun> origin = runs.findByIdForUpdate(runId);
        origin.ifPresent(run -> {
            if (!projectId.equals(run.projectId()) || !run.status().isTerminal())
                throw new ProposalExecutionConflictException();
        });
        return origin;
    }

    /** No other task locks are taken here: only read ownership while holding the stable project mutex. */
    public void lockProjectBeforeAuthorization(UUID projectId) {
        String scope = RuntimeExecutionProtocol.projectScope(projectId);
        try {
            // Concurrent decisions wait for the preceding short command, then observe ACCEPTED/REJECTED.
            scopeMutex.lock(java.util.List.of(scope));
        } catch (com.specagent.runtime.execution.ExecutionScopeUnavailableException unavailable) {
            throw new ProposalExecutionConflictException();
        }
        Boolean busy = jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM runtime_scope_owners owner JOIN runtime_tasks task ON task.id=owner.task_id
                    WHERE owner.scope_key=:scope AND task.state='LEASED' AND task.lease_until>clock_timestamp()
                        AND (task.deadline_at IS NULL OR task.deadline_at>clock_timestamp())
                ) OR EXISTS(
                    SELECT 1 FROM agent_runs WHERE project_id=:project
                        AND status NOT IN ('created','completed','failed')
                )
                """, java.util.Map.of("scope", scope, "project", projectId), Boolean.class);
        if (!Boolean.FALSE.equals(busy)) throw new ProposalExecutionConflictException();
    }
}
