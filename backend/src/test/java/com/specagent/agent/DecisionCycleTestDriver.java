package com.specagent.agent;

import com.specagent.agent.runtime.AgentRun;

import com.specagent.agent.runtime.RunService;
import com.specagent.agent.runtime.RunWorker;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 文件名:DecisionCycleTestDriver.java
 *
 * 测试目标:仅供测试使用的异步问题草稿驱动:入队 DRAFT_QUESTION run 并通过 worker
 * 同步执行。让 fixture 精确走生产草稿路径,使隔离/恢复类测试套件覆盖的正是生产逻辑。
 */
@Component
public class DecisionCycleTestDriver {

    private final RunService runService;
    private final RunWorker worker;

    public DecisionCycleTestDriver(RunService runService, RunWorker worker) {
        this.runService = runService;
        this.worker = worker;
    }

    /**
     * 在项目活动路由上起草下一个问题,并把 run 驱动到终态。领取时按入队的
     * run id 精确定位,因此绝不会误取无关的排队行。
     */
    public AgentRun draftQuestion(UUID projectId) {
        AgentRun enqueued = runService.createQueuedDraftQuestion(projectId);
        var claimed = runService.claimDecisionCycleRun(enqueued.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Expected queued decision-cycle run " + enqueued.id()));
        worker.executeRun(claimed);
        return runService.getRun(enqueued.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Run disappeared: " + enqueued.id()));
    }

    /**
     * 通过生产工件路径,在项目活动路由上生成规格快照。
     */
    public AgentRun generateSpec(UUID projectId) {
        AgentRun enqueued = runService.createQueuedArtifactGeneration(projectId);
        var claimed = runService.claimNextArtifact()
                .filter(run -> run.id().equals(enqueued.id()))
                .orElseThrow(() -> new IllegalStateException(
                        "Expected queued artifact run " + enqueued.id()));
        worker.executeRun(claimed);
        return runService.getRun(enqueued.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Run disappeared: " + enqueued.id()));
    }

    /**
     * 通过生产替换路径,在其显式来源路由上重新生成指定节点。
     */
    public AgentRun regenerateNode(UUID projectId, UUID sourceRouteId,
                                   UUID nodeId, String instruction) {
        AgentRun enqueued = runService.createQueuedRegenerate(
                projectId, sourceRouteId, nodeId, instruction);
        var claimed = runService.claimNextRegenerate()
                .filter(run -> run.id().equals(enqueued.id()))
                .orElseThrow(() -> new IllegalStateException(
                        "Expected queued regenerate run " + enqueued.id()));
        worker.executeRun(claimed);
        return runService.getRun(enqueued.id())
                .orElseThrow(() -> new IllegalStateException(
                        "Run disappeared: " + enqueued.id()));
    }
}
