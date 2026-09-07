package com.specagent.agent;

import com.specagent.agent.loop.ContinuationCheckRepository;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Atomic run terminalization: the run status transition, its terminal
 * runtime event, and its continuation-check request commit in ONE transaction.
 *
 * <p>The NodeQuery result contract derives semantic terminal outcomes
 * (POLICY_DENIED, MUTATION_NOT_CONFIRMABLE) from durable runtime events, never
 * from a trace string. Without a shared commit, a poll could observe the run
 * as COMPLETED before the required semantic event row exists — an externally
 * visible transient where {@code status == COMPLETED} but the required event
 * is absent. This boundary makes the two writes externally visible together,
 * so no observer can ever see COMPLETED without the semantic event.
 *
 * <p>Slice 3C adds the continuation outbox to the same commit: the terminal
 * status, the terminal event, and the {@code agent_run_continuation_checks}
 * request become visible together. The worker's afterCommit dispatch is only
 * the low-latency fast path; a crash after COMMIT but before dispatch leaves
 * a pending check row for the recovery scanner. No semantic fields are stored
 * — the coordinator re-reads durable run facts to decide.
 */
@Service
public class AgentRunTerminalizationService {

    private final AgentRunService agentRunService;
    private final AgentRunEventService eventService;
    private final ContinuationCheckRepository checkRepository;

    public AgentRunTerminalizationService(AgentRunService agentRunService,
                                          AgentRunEventService eventService,
                                          ContinuationCheckRepository checkRepository) {
        this.agentRunService = agentRunService;
        this.eventService = eventService;
        this.checkRepository = checkRepository;
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
        checkRepository.request(runId);
    }

    /**
     * Terminal without a semantic event (deny branch): COMPLETED and the
     * continuation-check request commit together. No new event semantics are
     * invented — the existing trace reason stays the deny evidence.
     */
    @Transactional
    public void completeWithCheck(UUID runId,
                                  AgentRunStatus status,
                                  String trace) {
        agentRunService.complete(runId, status, trace);
        checkRepository.request(runId);
    }

    /**
     * Slice 5 terminal-response terminal: the user-visible message event,
     * the run COMPLETED transition, the RUN_COMPLETED marker, and the
     * continuation-check request commit together. The RESPOND_MESSAGE row is
     * the single source of truth for the terminal message — the read model
     * and API derive it from this event, never from the trace string or a
     * second message store. The coordinator reads the same event to park
     * the chain (TERMINAL_RESPONSE), so no child follows a response.
     */
    @Transactional
    public void completeWithResponse(UUID runId,
                                     AgentRunStatus status,
                                     String trace,
                                     UUID producedNodeId,
                                     String message,
                                     Map<String, Object> completedPayload) {
        if (producedNodeId != null) {
            agentRunService.markPersistedNode(runId, producedNodeId, trace);
        }
        agentRunService.complete(runId, status, trace);
        if (message != null) {
            eventService.append(runId, AgentRunPhase.COMPLETED,
                    com.specagent.agent.runevent.AgentRunEventTypes.RESPOND_MESSAGE_EVENT,
                    Map.of("message", message));
        }
        eventService.append(runId, AgentRunPhase.COMPLETED, "RUN_COMPLETED",
                completedPayload);
        checkRepository.request(runId);
    }
}