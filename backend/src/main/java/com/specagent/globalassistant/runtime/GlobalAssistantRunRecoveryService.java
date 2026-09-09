package com.specagent.globalassistant.runtime;
import com.specagent.globalassistant.conversation.GlobalAssistantEventType;
import com.specagent.globalassistant.conversation.GlobalAssistantRun;
import com.specagent.globalassistant.conversation.GlobalAssistantRunRepository;
import com.specagent.globalassistant.conversation.GlobalAssistantRunStatus;
import com.specagent.globalassistant.stream.GlobalAssistantRunEventService;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
/**
 * Startup orphan recovery for the single-instance V1 executor.
 * Persisted CREATED/RUNNING runs from a dead process terminalize as honest
 * failures: no automatic tool replay, no durable side-effect retry.
 */
@Service
public class GlobalAssistantRunRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(GlobalAssistantRunRecoveryService.class);
    private final GlobalAssistantRunRepository runs;
    private final GlobalAssistantRunEventService events;
    public GlobalAssistantRunRecoveryService(GlobalAssistantRunRepository runs,
            GlobalAssistantRunEventService events) {
        this.runs = runs;
        this.events = events;
    }
    @EventListener(ApplicationReadyEvent.class)
    public void recoverOnStartup() {
        try {
            int recovered = recoverOrphans();
            if (recovered > 0) {
                log.warn("Global assistant orphan runs recovered as interrupted: count={}", recovered);
            }
        } catch (Exception ex) {
            log.warn("Global assistant orphan recovery failed: error={}", ex.getClass().getSimpleName());
        }
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
