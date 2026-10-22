package com.specagent.capability;

import com.specagent.common.DeferredCompletionRecoveryPort;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Uses the existing continuation poller. The committed business outcome is the
 * durable event; delivery requires no live caller, callback, or second executor. */
@Service
public class RebuildWaitCompletionRecovery implements DeferredCompletionRecoveryPort {
    private final RebuildWaitRepository waits;
    private final CapabilityInvocationRepository invocations;
    private final ObjectProvider<CapabilityRuntime> runtime;
    private final TransactionTemplate transactions;
    public RebuildWaitCompletionRecovery(RebuildWaitRepository waits,CapabilityInvocationRepository invocations,
            ObjectProvider<CapabilityRuntime> runtime,PlatformTransactionManager manager) {
        this.waits=waits;this.invocations=invocations;this.runtime=runtime;this.transactions=new TransactionTemplate(manager);
    }
    @Override public void recover(int limit) {
        for(UUID id:waits.pending(limit)) {
            try { transactions.executeWithoutResult(tx->complete(id)); }
            catch(RuntimeException ex) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Rebuild readiness delivery deferred for {} ({})",
                        id,ex.getClass().getSimpleName());
            }
        }
    }
    private void complete(UUID id) {
        var invocation=invocations.findById(id).orElse(null);
        if(invocation==null || invocation.status()!=CapabilityResult.Status.PENDING) return;
        var task=waits.bound(id).orElse(null);
        if(task==null || task.state()==null) return;
        var status=RetrievalAwaitGenerationCapability.terminalStatus(task.state());
        runtime.getObject().completeDeferred(RetrievalAwaitGenerationCapability.result(id,invocation.invocationKey(),task,status));
    }
}
