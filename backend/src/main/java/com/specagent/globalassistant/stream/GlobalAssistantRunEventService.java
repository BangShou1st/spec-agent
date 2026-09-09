package com.specagent.globalassistant.stream;
import com.specagent.globalassistant.conversation.GlobalAssistantRunEvent;
import com.specagent.globalassistant.conversation.GlobalAssistantRunEventRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/**
 * Single path for persisted public run events. Events publish to live
 * subscribers only after their transaction commits, so a rolled-back
 * transaction never emits a ghost event. No broker framework.
 */
@Service
public class GlobalAssistantRunEventService {
    private final GlobalAssistantRunEventRepository events;
    private final GlobalAssistantStreamService streams;
    public GlobalAssistantRunEventService(GlobalAssistantRunEventRepository events,
            GlobalAssistantStreamService streams) {
        this.events = events;
        this.streams = streams;
    }
    @Transactional
    public GlobalAssistantRunEvent append(UUID runId, String type, Map<String, Object> payload) {
        GlobalAssistantRunEvent appended = events.append(runId, type, payload);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    streams.publish(runId, appended);
                }
            });
        } else {
            streams.publish(runId, appended);
        }
        return appended;
    }
    public List<GlobalAssistantRunEvent> findByRun(UUID runId) {
        return events.findByRun(runId);
    }
    public List<GlobalAssistantRunEvent> findAfter(UUID runId, int cursor) {
        return events.findAfter(runId, cursor);
    }
}
