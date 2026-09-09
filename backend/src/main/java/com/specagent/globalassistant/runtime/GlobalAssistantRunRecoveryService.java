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
 * failures: no automatic tool replay, no durable side-effect retry. This bean
 * owns the transaction; the separate listener bean owns the event
 * subscription so the startup path never relies on proxy self-invocation.
 */
@Service
public class GlobalAssistantRunRecoveryService {
    private final GlobalAssistantRunRepository runs;
    private final GlobalAssistantRunEventService events;
    public GlobalAssistantRunRecoveryService(GlobalAssistantRunRepository runs,
            GlobalAssistantRunEventService events) {
        this.runs = runs;
        this.events = events;
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
        return orphans.size();
    }
}
