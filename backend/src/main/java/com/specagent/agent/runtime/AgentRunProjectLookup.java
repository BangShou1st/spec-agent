package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRun;
import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.broker.RunProjectLookup;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 文件名:AgentRunProjectLookup.java
 *
 * 用途:{@link RunProjectLookup} 的 runtime 侧实现,基于持久化的 AgentRun
 * 查询某个 run 所属的项目 ID。放在 runtime 包内是因为这里允许访问 repository,
 * 供 broker 等外层通过端口接口调用。
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
