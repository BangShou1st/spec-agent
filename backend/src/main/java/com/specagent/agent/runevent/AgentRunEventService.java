package com.specagent.agent.runevent;

import com.specagent.common.Ids;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文件名:AgentRunEventService.java
 *
 * 用途:运行事件的只追加(append-only)记录服务——每次运行阶段
 * 迁移和已脱敏的模型推理调用都会成为一条事件;事件绝不重写。
 */
@Service
public class AgentRunEventService {

    private final AgentRunEventRepository repository;

    public AgentRunEventService(AgentRunEventRepository repository) {
        this.repository = repository;
    }

    public void append(UUID runId, AgentRunPhase phase, String eventType, Map<String, Object> payload) {
        repository.append(new AgentRunEvent(Ids.random(), runId, 0, phase, eventType,
                payload == null ? Map.of() : payload, Instant.now()));
    }

    public List<AgentRunEvent> findByRunId(UUID runId) {
        return repository.findByRunId(runId);
    }
}
