package com.specagent.agent.runtime;

import com.specagent.agent.snapshot.CapabilityObservationVisibility;
import com.specagent.agent.snapshot.RunAttributionLookupPort;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:RunAttributionLookupAdapter.java
 *
 * 用途:{@link RunAttributionLookupPort} 的 runtime 侧适配器:把 run 投影成
 * snapshot builder 需要的归属信息对(routeId + inputNodeId)。映射逻辑放在
 * runtime 包内,是为了让 snapshot 包不依赖 run 持久化类型。
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
