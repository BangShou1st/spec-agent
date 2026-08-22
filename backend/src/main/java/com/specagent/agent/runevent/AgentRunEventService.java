package com.specagent.agent.runevent;

import com.specagent.common.Ids;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Append-only run event recording. Every run phase transition and sanitized
 * model-inference call becomes one event; events are never rewritten.
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
