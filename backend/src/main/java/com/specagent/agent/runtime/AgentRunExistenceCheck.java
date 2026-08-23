package com.specagent.agent.runtime;

import com.specagent.agent.AgentRunService;
import com.specagent.agent.broker.RunExistenceCheck;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Runtime implementation of {@link RunExistenceCheck} backed by durable
 * AgentRun persistence. Lives in the runtime package where repository
 * access is permitted.
 */
@Component
public class AgentRunExistenceCheck implements RunExistenceCheck {

    private final AgentRunService agentRunService;

    public AgentRunExistenceCheck(AgentRunService agentRunService) {
        this.agentRunService = agentRunService;
    }

    @Override
    public boolean exists(UUID runId) {
        return agentRunService.getRun(runId).isPresent();
    }
}
