package com.specagent.agent;

import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Atomic run terminalization: the run status transition and its terminal
 * runtime event commit in ONE transaction.
 *
 * <p>The NodeQuery result contract derives semantic terminal outcomes
 * (POLICY_DENIED, MUTATION_NOT_CONFIRMABLE) from durable runtime events, never
 * from a trace string. Without a shared commit, a poll could observe the run
 * as COMPLETED before the required semantic event row exists — an externally
 * visible transient where {@code status == COMPLETED} but the required event
 * is absent. This boundary makes the two writes externally visible together,
 * so no observer can ever see COMPLETED without the semantic event.
 */
@Service
public class AgentRunTerminalizationService {

    private final AgentRunService agentRunService;
    private final AgentRunEventService eventService;

    public AgentRunTerminalizationService(AgentRunService agentRunService,
                                          AgentRunEventService eventService) {
        this.agentRunService = agentRunService;
        this.eventService = eventService;
    }

    /**
     * Marks the run terminal and appends its terminal event atomically. A
     * reader under READ_COMMITTED sees either both writes or neither.
     */
    @Transactional
    public void completeWithEvent(UUID runId,
                                  AgentRunStatus status,
                                  String trace,
                                  AgentRunPhase phase,
                                  String eventType,
                                  Map<String, Object> payload) {
        agentRunService.complete(runId, status, trace);
        eventService.append(runId, phase, eventType, payload);
    }
}