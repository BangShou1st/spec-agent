package com.specagent.agent;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Records a terminal agent run failure in its own transaction.
 *
 * <p>A failed run must remain queryable even when the surrounding agent cycle
 * throws: without this separate boundary, an enclosing transaction would roll
 * the FAILED update back together with the run creation.
 */
@Service
public class AgentRunFailureService {

    private final AgentRunRepository agentRunRepository;

    public AgentRunFailureService(AgentRunRepository agentRunRepository) {
        this.agentRunRepository = agentRunRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID runId, String trace) {
        agentRunRepository.fail(runId, trace);
    }
}