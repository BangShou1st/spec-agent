package com.specagent.agent.runtime;

import com.specagent.runtime.execution.RuntimeTask;
import com.specagent.runtime.execution.RuntimeTaskRepository;
import com.specagent.runtime.execution.RuntimeTaskState;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProposalOriginGuardTest {
    private final RuntimeTaskRepository tasks = mock(RuntimeTaskRepository.class);
    private final AgentRunRepository runs = mock(AgentRunRepository.class);
    private final com.specagent.runtime.execution.RuntimeScopeMutex mutex = mock(com.specagent.runtime.execution.RuntimeScopeMutex.class);
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc = mock(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class);
    private final ProposalOriginGuard guard = new ProposalOriginGuard(tasks, runs, mutex, jdbc);
    private final UUID runId = UUID.randomUUID(), project = UUID.randomUUID();

    @Test void leasedOriginRefusesAcceptanceEvenBeforeBusinessRunLock() {
        RuntimeTask task = mock(RuntimeTask.class);
        when(task.state()).thenReturn(RuntimeTaskState.LEASED);
        when(tasks.lockWorkspaceRunTask(runId)).thenReturn(Optional.of(task));
        assertThatThrownBy(() -> guard.lockTaskBeforeAuthorization(runId))
                .isInstanceOf(ProposalExecutionConflictException.class);
        verifyNoInteractions(runs);
    }

    @Test void terminalTaskAndRunAreLockedInOrder() {
        RuntimeTask task = mock(RuntimeTask.class);
        when(task.state()).thenReturn(RuntimeTaskState.SUCCEEDED);
        when(tasks.lockWorkspaceRunTask(runId)).thenReturn(Optional.of(task));
        AgentRun run = mock(AgentRun.class);
        when(run.projectId()).thenReturn(project);
        when(run.status()).thenReturn(AgentRunStatus.COMPLETED);
        when(runs.findByIdForUpdate(runId)).thenReturn(Optional.of(run));
        guard.lockTaskBeforeAuthorization(runId);
        assertThat(guard.lockOriginAfterAuthorization(runId, project)).contains(run);
        var ordered = inOrder(tasks, runs);
        ordered.verify(tasks).lockWorkspaceRunTask(runId);
        ordered.verify(runs).findByIdForUpdate(runId);
    }

    @Test void noTaskDoesNotBypassRunningLegacyRunProtection() {
        when(tasks.lockWorkspaceRunTask(runId)).thenReturn(Optional.empty());
        AgentRun run = mock(AgentRun.class);
        when(run.projectId()).thenReturn(project);
        when(run.status()).thenReturn(AgentRunStatus.RUNNING);
        when(runs.findByIdForUpdate(runId)).thenReturn(Optional.of(run));
        guard.lockTaskBeforeAuthorization(runId);
        assertThatThrownBy(() -> guard.lockOriginAfterAuthorization(runId, project))
                .isInstanceOf(ProposalExecutionConflictException.class);
    }

    @Test void persistedOriginFromDifferentProjectRefusesAcceptance() {
        AgentRun run = mock(AgentRun.class);
        when(run.projectId()).thenReturn(UUID.randomUUID());
        when(runs.findByIdForUpdate(runId)).thenReturn(Optional.of(run));
        assertThatThrownBy(() -> guard.lockOriginAfterAuthorization(runId, project))
                .isInstanceOf(ProposalExecutionConflictException.class);
    }

    @Test void externalProposalWithoutPersistedRunRetainsExplicitGhostOriginSemantics() {
        when(tasks.lockWorkspaceRunTask(runId)).thenReturn(Optional.empty());
        when(runs.findByIdForUpdate(runId)).thenReturn(Optional.empty());
        guard.lockTaskBeforeAuthorization(runId);
        assertThat(guard.lockOriginAfterAuthorization(runId, project)).isEmpty();
        assertThat(guard.lockOriginAfterAuthorization(null, project)).isEmpty();
    }

    @Test void conflictingProjectMutexRefusesWithoutReadingOtherTasks() {
        doThrow(new com.specagent.runtime.execution.ExecutionScopeUnavailableException()).when(mutex)
                .lock(java.util.List.of(com.specagent.runtime.execution.RuntimeExecutionProtocol.projectScope(project)));
        assertThatThrownBy(() -> guard.lockProjectBeforeAuthorization(project)).isInstanceOf(ProposalExecutionConflictException.class);
        verifyNoInteractions(jdbc);
    }

    @Test void otherActiveRunInProjectRefusesManualAcceptance() {
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.eq(Boolean.class)))
                .thenReturn(true);
        assertThatThrownBy(() -> guard.lockProjectBeforeAuthorization(project)).isInstanceOf(ProposalExecutionConflictException.class);
        verifyNoInteractions(tasks,runs);
    }

    @Test void emptyProjectScopeIsProtectedBeforeOwnershipRead() {
        when(jdbc.queryForObject(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.eq(Boolean.class)))
                .thenReturn(false);
        assertThatCode(() -> guard.lockProjectBeforeAuthorization(project)).doesNotThrowAnyException();
        var ordered = inOrder(mutex,jdbc);
        ordered.verify(mutex).lock(java.util.List.of(com.specagent.runtime.execution.RuntimeExecutionProtocol.projectScope(project)));
        ordered.verify(jdbc).queryForObject(org.mockito.ArgumentMatchers.anyString(),org.mockito.ArgumentMatchers.anyMap(),org.mockito.ArgumentMatchers.eq(Boolean.class));
    }
}
