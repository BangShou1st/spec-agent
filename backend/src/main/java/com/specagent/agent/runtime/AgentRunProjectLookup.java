package com.specagent.agent.runtime;

import com.specagent.agent.AgentRun;
import com.specagent.agent.AgentRunService;
import com.specagent.agent.broker.RunProjectLookup;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Runtime implementation of {@link RunProjectLookup} backed by durable AgentRun
 * persistence. Lives in the runtime package where repository access is permitted.
 */
@Component
public class AgentRunProjectLookup implements RunProjectLookup {

    private final AgentRunService agentRunService;

    public AgentRunProjectLookup(AgentRunService agentRunService) {
        this.agentRunService = agentRunService;
    }

    @Override
    public UUID projectIdOf(UUID runId) {
        if (runId == null) {
            return null;
        }
        return agentRunService.getRun(runId).map(AgentRun::projectId).orElse(null);
    }
}
