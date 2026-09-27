package com.specagent.agent.runtime;

import com.specagent.agent.runtime.ContinuationCheckRepository;
import com.specagent.agent.runevent.AgentRunEventService;
import com.specagent.agent.runevent.AgentRunPhase;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * 文件名:AgentRunTerminalizationService.java
 *
 * 用途:run 终态化的原子边界:run 状态迁移、其终止运行时事件、续跑检查
 * (continuation-check)请求,三者在同一个事务内一起提交。
 *
 * NodeQuery 结果契约依据持久化的运行时事件(而非 trace 字符串)推导出语义
 * 终态(POLICY_DENIED、MUTATION_NOT_CONFIRMABLE)。若没有这里的共享提交,
 * 轮询方可能先观察到 run 已 COMPLETED 而必需的语义事件行尚未写入——即出现
 * {@code status == COMPLETED} 但必需事件缺失的外部可见瞬态。此事务边界让两次
 * 写入对外同时可见,任何观察者都不会看到"有 COMPLETED 却没有语义事件"的状态。
 *
 * Slice 3C 把续跑 outbox 也并入同一提交:终态、终止事件与
 * {@code agent_run_continuation_checks} 请求同时对外可见。worker 的
 * afterCommit 分发只是低延迟快速路径;COMMIT 之后、分发之前崩溃会留下
 * 一条待处理的 check 行,由恢复扫描器兜底。这里不存储任何语义字段——
 * 由协调器重新读取持久化的 run 事实来做决策。
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
     * 原子地把 run 置为终态并追加其终止事件。READ_COMMITTED 隔离级别下的
     * 读方要么看到两次写入都生效,要么都看不到。
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
     * 终态但无语义事件(拒绝分支):COMPLETED 与续跑检查请求一起提交。
     * 不引入新的事件语义——既有的 trace 原因即拒绝证据。
     */
    @Transactional
    public void completeWithCheck(UUID runId,
                                  AgentRunStatus status,
                                  String trace) {
        agentRunService.complete(runId, status, trace);
        checkRepository.request(runId);
    }

    /**
     * Slice 5 的终态响应收尾:用户可见的消息事件、run 的 COMPLETED 迁移、
     * RUN_COMPLETED 标记事件、续跑检查请求,四者一起提交。RESPOND_MESSAGE
     * 事件行是终态消息的唯一事实来源——读模型与 API 从该事件派生消息,
     * 绝不从 trace 字符串或第二个消息存储读取。协调器读取同一事件来停靠
     * 链路(TERMINAL_RESPONSE),因此不会有子 run 跟在一条响应之后。
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