package com.specagent.agent.runtime;

import com.specagent.runtime.execution.TaskWriteGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.mockito.Mockito.*;

class ExecutionFenceProbeTransactionTest {
    private final PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
    private final TaskWriteGuard guard=mock(TaskWriteGuard.class);
    @AfterEach void reset() {TransactionSynchronizationManager.setActualTransactionActive(false);}

    @SuppressWarnings("unchecked")
    private ExecutionFence fence() {
        var leases=(ObjectProvider<ExecutorLease>)mock(ObjectProvider.class);
        var guards=(ObjectProvider<TaskWriteGuard>)mock(ObjectProvider.class);
        when(guards.getIfAvailable()).thenReturn(guard);
        var fence=new ExecutionFence(leases,mock(NamedParameterJdbcTemplate.class),guards,true);
        fence.setProbeTransactions(new TransactionTemplate(manager));
        return fence;
    }

    @Test void preModelProbeUsesShortTransactionAndCommitsBeforeReturning() {
        when(manager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        var fence=fence();
        var token=new com.specagent.runtime.execution.ExecutionToken(java.util.UUID.randomUUID(),1,
                java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),com.specagent.runtime.execution.RuntimeTaskFamily.WORKSPACE,java.util.UUID.randomUUID());
        com.specagent.runtime.execution.ExecutionTokenContext.runWith(token,fence::assertOwnership);
        var order=inOrder(manager,guard);
        order.verify(manager).getTransaction(any());
        order.verify(guard).lockOwnedTask(token);
        order.verify(manager).commit(any());
    }

    @Test void protectedWriteReusesBusinessTransactionInsteadOfStartingAnotherOne() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        var fence=fence();
        var token=new com.specagent.runtime.execution.ExecutionToken(java.util.UUID.randomUUID(),1,
                java.util.UUID.randomUUID(),java.util.UUID.randomUUID(),com.specagent.runtime.execution.RuntimeTaskFamily.WORKSPACE,java.util.UUID.randomUUID());
        com.specagent.runtime.execution.ExecutionTokenContext.runWith(token,fence::assertOwnership);
        verify(guard).lockOwnedTask(token);
        verifyNoInteractions(manager);
    }
}
