package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRunService;
import com.specagent.agent.broker.RunExistenceCheck;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 文件名:AgentRunExistenceCheck.java
 *
 * 用途:{@link RunExistenceCheck} 的 runtime 侧实现,基于持久化的 AgentRun
 * 判断 run 是否存在。放在 runtime 包内是因为这里允许访问 repository,
 * 供 broker 等外层通过端口接口调用。
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
