package com.specagent.agent.runtime;

import com.specagent.agent.snapshot.CapabilityObservationVisibility;
import com.specagent.agent.snapshot.RunAttributionLookupPort;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Runtime-side adapter for {@link RunAttributionLookupPort}: projects a run
 * into the attribution pair the snapshot builder needs. Keeping the mapping
 * here means the snapshot package stays free of run-persistence types.
 */
@Component
public class RunAttributionLookupAdapter implements RunAttributionLookupPort {

    private final AgentRunRepository agentRunRepository;

    public RunAttributionLookupAdapter(AgentRunRepository agentRunRepository) {
        this.agentRunRepository = agentRunRepository;
    }

    @Override
    public Optional<CapabilityObservationVisibility.RunAttribution> attributionOf(UUID runId) {
        return agentRunRepository.findById(runId)
                .map(run -> new CapabilityObservationVisibility.RunAttribution(
                        run.routeId(), run.inputNodeId()));
    }
}
