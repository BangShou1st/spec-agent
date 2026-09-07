package com.specagent.agent.loop;

import com.specagent.agent.AgentRunRepository;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunEventTypes;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * Chain-aware read service for autonomous run chains.
 * READ-ONLY: never creates children, never calls Coordinator, never runs models/actions.
 */
@Service
public class AgentRunChainReadService {

    public record AgentRunChainRead(UUID childRunId,
                                    boolean continuationPending,
                                    String respondMessage) {
    }

    private final AgentRunRepository agentRunRepository;
    private final ContinuationCheckRepository continuationCheckRepository;
    private final AgentRunEventService eventService;

    public AgentRunChainReadService(AgentRunRepository agentRunRepository,
                                    ContinuationCheckRepository continuationCheckRepository,
                                    AgentRunEventService eventService) {
        this.agentRunRepository = agentRunRepository;
        this.continuationCheckRepository = continuationCheckRepository;
        this.eventService = eventService;
    }

    public AgentRunChainRead read(UUID runId) {
        UUID childRunId = agentRunRepository.findChildByParentRunId(runId)
                .map(child -> child.id())
                .orElse(null);
        boolean continuationPending =
                continuationCheckRepository.findPendingByRunId(runId).isPresent();
        String respondMessage = eventService.findByRunId(runId).stream()
                .filter(e -> AgentRunEventTypes.RESPOND_MESSAGE_EVENT.equals(e.eventType()))
                .map(e -> e.payload().get("message"))
                .filter(value -> value instanceof String)
                .map(String.class::cast)
                .reduce((first, second) -> second)
                .orElse(null);
        return new AgentRunChainRead(childRunId, continuationPending, respondMessage);
    }
}
