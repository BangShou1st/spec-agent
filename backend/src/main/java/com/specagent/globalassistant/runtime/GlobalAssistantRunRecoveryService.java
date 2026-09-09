package com.specagent.globalassistant.runtime;
import com.specagent.globalassistant.conversation.GlobalAssistantEventType;
import com.specagent.globalassistant.conversation.GlobalAssistantRun;
import com.specagent.globalassistant.conversation.GlobalAssistantRunRepository;
import com.specagent.globalassistant.conversation.GlobalAssistantRunStatus;
import com.specagent.globalassistant.stream.GlobalAssistantRunEventService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
    private final GlobalAssistantRunEventService events;
    private final com.specagent.globalassistant.turn.PendingTurnRepository pending;
    private final com.specagent.globalassistant.turn.TurnHandoffService handoff;
    private final com.specagent.globalassistant.turn.RunDispatcher dispatcher;
    public GlobalAssistantRunRecoveryService(GlobalAssistantRunRepository runs,
            GlobalAssistantRunEventService events,
            com.specagent.globalassistant.turn.PendingTurnRepository pending,
            com.specagent.globalassistant.turn.TurnHandoffService handoff,
            com.specagent.globalassistant.turn.RunDispatcher dispatcher) {
        this.runs = runs;
        this.events = events;
        this.pending = pending;
        this.handoff = handoff;
        this.dispatcher = dispatcher;
    }
    @Transactional
    public int recoverOrphans() {
        List<GlobalAssistantRun> orphans = runs.findActiveRuns();
        for (GlobalAssistantRun orphan : orphans) {
            events.append(orphan.id(), GlobalAssistantEventType.RUN_FAILED, Map.of(
                    "errorCode", GlobalAssistantErrorCode.RUN_INTERRUPTED,
                    "reason", "The previous process stopped before this run finished."));
            runs.terminalize(orphan.id(), GlobalAssistantRunStatus.FAILED,
                    GlobalAssistantErrorCode.RUN_INTERRUPTED);
        }
        int stranded = 0;
        try {
            var pendings = pending.findStrandedPending();
            for (var pt : pendings) {
                try {
                    if (runs.findActiveByThread(pt.threadId()).isPresent()) {
                        continue;
                    }
                    var successor = handoff.tryHandoff(pt.threadId());
                    successor.ifPresent(s -> dispatcher.dispatch(s.run().threadId(), s.run().id(), s.message(), s.uiRequest()));
                    if (successor.isPresent()) {
                        stranded++;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return orphans.size() + stranded;
    }
}
