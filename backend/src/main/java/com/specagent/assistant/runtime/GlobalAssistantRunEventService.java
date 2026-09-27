package com.specagent.assistant.runtime;
import com.specagent.assistant.conversation.GlobalAssistantRunEvent;
import com.specagent.assistant.conversation.GlobalAssistantRunEventRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
/**
 * 文件名:GlobalAssistantRunEventService.java
 *
 * 用途:持久化 run 公开事件的唯一通道。事件只有在所属事务提交之后
 * 才会推送给在线订阅者,因此回滚的事务绝不会产生"幽灵事件"。
 * 不引入任何消息代理框架。
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
