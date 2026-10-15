package com.specagent.agent.runtime;

import com.specagent.runtime.execution.ExecutionScopeUnavailableException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RunWorkerScopeRefusalTest {
    @Test void legacyScopeTimeoutFailsMetadataWithoutStartingAnyModelCycle() {
        var failures=mock(AgentRunFailureService.class);
        var decision=mock(DecisionCycleService.class);
        var authorization=mock(WorkspaceExecutionAuthorization.class);
        var worker=new RunWorker(mock(RunService.class),mock(AgentRunService.class),failures,
                mock(com.specagent.agent.runevent.AgentRunEventService.class),mock(AnswerCycleService.class),decision,
                mock(ArtifactCycleService.class),mock(ReplacementCycleService.class),mock(NodeQueryService.class),
                mock(ContinuationCycleService.class),mock(ContinuationDispatchService.class),mock(ExecutionFence.class));
        worker.setAuthorization(authorization);
        var run=mock(AgentRun.class);
        var id=java.util.UUID.randomUUID();
        when(run.id()).thenReturn(id);
        when(authorization.authorize(id)).thenThrow(new ExecutionScopeUnavailableException());
        assertThatThrownBy(()->worker.executeClaimedRun(run)).isInstanceOf(ExecutionScopeUnavailableException.class);
        verify(failures).fail(id,"\"execution scope unavailable\"",RunFailureReasons.EXECUTION_SCOPE_UNAVAILABLE);
        verifyNoInteractions(decision);
        assertThat(RunFailureReasons.userCopyFor(RunFailureReasons.EXECUTION_SCOPE_UNAVAILABLE)).isNotBlank();
    }
}
