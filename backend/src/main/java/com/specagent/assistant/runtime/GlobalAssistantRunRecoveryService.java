package com.specagent.assistant.runtime;
import com.specagent.assistant.conversation.GlobalAssistantRun;
import com.specagent.assistant.conversation.GlobalAssistantRunRepository;
import java.util.List;
import org.springframework.stereotype.Service;
/**
 * Startup orphan recovery for the single-instance V1 executor.
 * Persisted CREATED/RUNNING runs from a dead process terminalize as honest
 * failures: no automatic tool replay, no durable side-effect retry.
 * Pending-steer recovery is delegated to TurnHandoffService so stranded
 * steers still hand off exactly once.
 */
@Service
public class GlobalAssistantRunRecoveryService {
    private final GlobalAssistantRunRepository runs;
    private final GlobalAssistantRunLifecycleService lifecycle;
    private final com.specagent.assistant.conversation.PendingTurnRepository pending;
    private final com.specagent.assistant.runtime.TurnHandoffService handoff;
    private final com.specagent.assistant.runtime.RunDispatcher dispatcher;
    public GlobalAssistantRunRecoveryService(GlobalAssistantRunRepository runs,
            GlobalAssistantRunLifecycleService lifecycle,
            com.specagent.assistant.conversation.PendingTurnRepository pending,
            com.specagent.assistant.runtime.TurnHandoffService handoff,
            com.specagent.assistant.runtime.RunDispatcher dispatcher) {
        this.runs = runs;
        this.lifecycle = lifecycle;
        this.pending = pending;
        this.handoff = handoff;
        this.dispatcher = dispatcher;
    }
    public int recoverOrphans() {
        List<GlobalAssistantRun> orphans = runs.findActiveRuns();
        int interrupted = 0;
        for (GlobalAssistantRun orphan : orphans) {
            try {
                lifecycle.interruptAndTerminalize(orphan.id());
                interrupted++;
            } catch (Exception ignored) {
            }
        }
        int stranded = 0;
        try {
            var pendings = pending.findStrandedPending();
            for (var pt : pendings) {
                try {
                    if (runs.findActiveByThread(pt.threadId()).isPresent()) {
                        continue;
                    }
                    var successor = handoff.tryHandoffAfterCommit(pt.threadId());
                    if (successor.isPresent()) {
                        var s = successor.get();
                        dispatcher.dispatch(s.run().threadId(), s.run().id(), s.message(), s.uiRequest());
                        stranded++;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return interrupted + stranded;
    }
}
