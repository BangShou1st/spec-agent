package com.specagent.agent.runtime;

import com.specagent.agent.action.*;
import com.specagent.agent.decision.AgentDecisionEngine;
import com.specagent.agent.policy.*;
import com.specagent.agent.protocol.*;
import com.specagent.agent.runevent.*;
import com.specagent.agent.snapshot.StaleContextChecker;
import com.specagent.workspace.context.ContextSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Drives the actual shared decision tail, asserting the fenced callback boundary. */
class WaitDecisionExecutionOfflineTest {
    @Test void subscriptionAndTerminalizationExecuteInTheSameFencedCallback() throws Exception {
        execute(false);
    }
    @Test void subscriptionFailureCannotTerminalizeOrFallBackToLegacyWaitNoop() throws Exception {
        execute(true);
    }
    void execute(boolean refuse) throws Exception {
        var base=AgentContracts.read(Files.readString(Path.of("../contracts/fixtures/agent-input-v4-wait.json")),AgentRequestEnvelope.class);
        var gate=new ActionEligibilityGate("enforced");gate.setDependencies(ignored->base.pendingDependencies());
        var request=gate.prepareDecisionRequest(new AgentRequestEnvelope("agent-input.v2",base.runId(),base.event(),base.snapshot(),base.capabilities(),base.decisionBudget()));
        var p=request.pendingDependencies();var proposal=new ActionProposal("WAIT",Map.of("dependencyRef",p.dependencies().getFirst().invocationId().toString()),
                p.snapshotId(),p.contextHash(),List.of(),UUID.randomUUID(),request.runId().toString(),List.of());
        var engine=mock(AgentDecisionEngine.class);when(engine.runDecision(request)).thenReturn(new AgentResponseEnvelope("agent-decision.v4",request.runId(),null,
                new ObservationView(List.of(),List.of(),List.of(),List.of()),proposal,new UsageView(1,List.of()),Map.of(),request.actionEligibility().version(),request.actionEligibility().basisHash(),List.of()));
        var policy=mock(AdvisorPolicyEngine.class);var context=new ActionExecutionContext(request.runId(),p.projectId(),p.routeId(),p.snapshotId(),p.anchorNodeId(),null,null);
        when(policy.evaluate(proposal,context)).thenReturn(PolicyDecision.autoExecute(MutationClass.READ_ONLY_INTERNAL));
        var terminal=mock(AgentRunTerminalizationService.class);var dependencies=mock(WaitDependencyService.class);
        var actions=mock(ActionExecutor.class);var fence=mock(ExecutionFence.class);var tx=mock(TransactionTemplate.class);
        var inside=new AtomicBoolean();
        doAnswer(call->{java.util.function.Consumer<TransactionStatus> action=call.getArgument(0);inside.set(true);
            try {action.accept(mock(TransactionStatus.class));} finally {inside.set(false);}return null;}).when(tx).executeWithoutResult(any());
        doAnswer(call->{assertThat(inside.get()).isTrue();if(refuse)throw new IllegalStateException("subscription rejected");return null;})
                .when(dependencies).subscribe(request,proposal);
        doAnswer(call->{assertThat(inside.get()).isTrue();return null;}).when(terminal).completeWithCheck(eq(request.runId()),eq(AgentRunStatus.COMPLETED),anyString());
        var service=new DecisionExecutionService(engine,policy,actions,mock(AgentProposalService.class),terminal,mock(AgentRunEventService.class),
                fence,tx,mock(StaleContextChecker.class),gate,mock(AgentTracePort.class),mock(RunProgressRecorder.class));
        service.setWaitDependencies(dependencies);
        var snapshot=mock(ContextSnapshot.class);when(snapshot.id()).thenReturn(p.snapshotId());when(snapshot.projectId()).thenReturn(p.projectId());when(snapshot.routeId()).thenReturn(p.routeId());
        if(refuse) {
            assertThatThrownBy(()->service.execute(snapshot,request,context,"created",">",Map.of())).hasMessage("subscription rejected");
            verifyNoInteractions(terminal);
        } else {
            assertThat(service.execute(snapshot,request,context,"created",">",Map.of()).outcome()).isEqualTo("waiting_dependency");
            var order=inOrder(fence,dependencies,terminal);
            order.verify(fence,atLeastOnce()).lockOwnershipForWrite();
            order.verify(dependencies).subscribe(request,proposal);
            order.verify(terminal).completeWithCheck(eq(request.runId()),eq(AgentRunStatus.COMPLETED),anyString());
        }
        verifyNoInteractions(actions);
    }
}
