package com.specagent.agent;

import com.specagent.agent.runtime.AgentRun;

import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 文件名:AnswerCycleTestDriver.java
 *
 * 测试目标:仅供测试使用的异步 ANSWER_CYCLE 驱动:入队一个 run,并通过 worker
 * 同步执行它。替代已废弃的同步答案命令,使恢复/隔离类测试套件精确走生产答题路径。
 *
 * 【领取所有权】。驱动始终按 id 领取自己入队的那个 run
 * ({@link RunService#claimAnswerCycleRun(java.util.UUID)}),绝不领取队头
 * ({@code claimNextAnswerCycle})。ANSWER_CYCLE 队列与生产 {@code RunWorker} 轮询器
 * 以及同一数据库中的其他 fixture 共享,按队列领取可能把一个无关的排队 run 交给本驱动
 * (并执行它),或让自己的 run 被其他测试的排序挡住。若竞争消费者已抢先领取,按 id
 * 领取会返回空,驱动将失败(fail closed)而不是执行外来 run。
 */
@Component
public class AnswerCycleTestDriver {

    private final RunService runService;
    private final RunWorker worker;

    public AnswerCycleTestDriver(RunService runService, RunWorker worker) {
        this.runService = runService;
        this.worker = worker;
    }

    /**
     * 向活动路由末梢提交一段自由文本答案,并把 run 驱动到终态。
     * 返回 run 及其产出的记录。
     */
    public SubmittedAnswer submitFreeText(UUID projectId, String freeText) {
        return submit(projectId, "ANSWER_TIP", null, freeText, null);
    }

    /** 从已持久化的答案继续(修复路径)。 */
    public SubmittedAnswer resumeAnswer(UUID projectId, UUID answerId) {
        return submit(projectId, "RESUME_ANSWER", null, null, answerId);
    }

    /**
     * 针对指定节点入队一个答题 run,但不执行。用于布置在 worker 领取前
     * 目标可能已过期的 run(失败路径 fixture)。
     */
    public UUID enqueueOnly(UUID projectId, String operation, UUID nodeId,
                            UUID selectedOptionId, String freeText, UUID answerId) {
        return runService.createQueuedRunWithInput(
                projectId, operation, nodeId, selectedOptionId, freeText, answerId);
    }

    private SubmittedAnswer submit(UUID projectId, String operation,
                                   UUID nodeId, String freeText, UUID answerId) {
        UUID runId = runService.createQueuedRunWithInput(
                projectId, operation, nodeId, null, freeText, answerId);
        // 精确领取本驱动入队的那个 run:ANSWER_CYCLE 队列是共享的,
        // "最旧的排队 run"不一定是我们的。
        var claimed = runService.claimAnswerCycleRun(runId)
                .orElseThrow(() -> new IllegalStateException(
                        "Enqueued answer-cycle run is not claimable: " + runId));
        worker.executeRun(claimed);
        AgentRun run = runService.getRun(runId)
                .orElseThrow(() -> new IllegalStateException("Run disappeared: " + runId));
        return new SubmittedAnswer(run);
    }

    /** 一次已提交答题循环的事后视图。 */
    public record SubmittedAnswer(AgentRun run) {

        public UUID answerId() {
            return run.producedAnswerId();
        }

        public UUID patchId() {
            return run.producedPatchId();
        }

        public UUID producedNodeId() {
            return run.producedNodeId();
        }
    }
}
