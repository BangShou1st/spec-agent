package com.specagent.agent.runtime;

import com.specagent.agent.runtime.AgentRunRepository;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunEventTypes;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 文件名:AgentRunChainReadService.java
 *
 * 用途:面向自治续跑链的只读查询服务。按 runId 读取该 run 的续跑链路状态:
 * 是否已有子 run、是否存在待处理的 continuation check,以及最后一条 RESPOND 消息。
 * 只读:绝不创建子 run、绝不调用 Coordinator、绝不执行模型/动作。
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
