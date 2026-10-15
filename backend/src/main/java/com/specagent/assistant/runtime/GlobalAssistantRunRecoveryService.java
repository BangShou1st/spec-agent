package com.specagent.assistant.runtime;
import com.specagent.assistant.conversation.GlobalAssistantRun;
import com.specagent.assistant.conversation.GlobalAssistantRunRepository;
import java.util.List;
import org.springframework.stereotype.Service;
/**
 * 文件名:GlobalAssistantRunRecoveryService.java
 *
 * 用途:全局助手活跃 Run 的启动期孤儿恢复。已持久化的 CREATED/RUNNING
 * run 如果属于已死进程,会被诚实地质化为失败:不自动重放工具,
 * 也不重试持久化副作用。待处理的 steer 恢复委托给 TurnHandoffService,
 * 确保被搁置的 steer 依然恰好交接一次。
 *
 * <p><b>多 Worker(任务级所有权)下的关键区分(design §11、§12)。</b>本服务
 * 原本依赖"同一时刻至多一个执行器进程"这一单实例边界,会把所有活跃 Run 都当作
 * 已死执行器的遗留物。多 Worker 下那是<b>错误</b>的:另一个实例正在执行的 Run
 * 同样是活跃状态。因此任务级模式下,持有<b>存活租约</b>的 Run 被跳过——它们的
 * 归属由租约与过期扫描决定,绝不在这里被本进程的启动恢复终止。
 */
@Service
public class GlobalAssistantRunRecoveryService {
    private final GlobalAssistantRunRepository runs;
    private final GlobalAssistantRunLifecycleService lifecycle;
    private final com.specagent.assistant.conversation.PendingTurnRepository pending;
    private final com.specagent.assistant.runtime.TurnHandoffService handoff;
    private final com.specagent.assistant.runtime.RunDispatcher dispatcher;
    /**
     * 任务级所有权模式下用于识别"属于其他存活 Worker 的活跃 GA Run"。
     * 单执行器模式下为 null,行为与历史完全一致。
     */
    private final com.specagent.runtime.execution.RuntimeTaskRepository runtimeTasks;

    public GlobalAssistantRunRecoveryService(GlobalAssistantRunRepository runs,
            GlobalAssistantRunLifecycleService lifecycle,
            com.specagent.assistant.conversation.PendingTurnRepository pending,
            com.specagent.assistant.runtime.TurnHandoffService handoff,
            com.specagent.assistant.runtime.RunDispatcher dispatcher) {
        this(runs, lifecycle, pending, handoff, dispatcher,
                (com.specagent.runtime.execution.RuntimeTaskRepository) null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public GlobalAssistantRunRecoveryService(GlobalAssistantRunRepository runs,
            GlobalAssistantRunLifecycleService lifecycle,
            com.specagent.assistant.conversation.PendingTurnRepository pending,
            com.specagent.assistant.runtime.TurnHandoffService handoff,
            com.specagent.assistant.runtime.RunDispatcher dispatcher,
            org.springframework.beans.factory.ObjectProvider<
                    com.specagent.runtime.execution.RuntimeTaskRepository> runtimeTasks) {
        this(runs, lifecycle, pending, handoff, dispatcher,
                runtimeTasks == null ? null : runtimeTasks.getIfAvailable());
    }

    private GlobalAssistantRunRecoveryService(GlobalAssistantRunRepository runs,
            GlobalAssistantRunLifecycleService lifecycle,
            com.specagent.assistant.conversation.PendingTurnRepository pending,
            com.specagent.assistant.runtime.TurnHandoffService handoff,
            com.specagent.assistant.runtime.RunDispatcher dispatcher,
            com.specagent.runtime.execution.RuntimeTaskRepository runtimeTasks) {
        this.runs = runs;
        this.lifecycle = lifecycle;
        this.pending = pending;
        this.handoff = handoff;
        this.dispatcher = dispatcher;
        this.runtimeTasks = runtimeTasks;
    }

    public int recoverOrphans() {
        List<GlobalAssistantRun> active = runs.findActiveRuns();
        List<GlobalAssistantRun> orphans = active;
        if (runtimeTasks != null) {
            // 多 Worker:绝不终止其他存活实例正在执行的 Run(design §12)。
            java.util.Set<java.util.UUID> liveOwned = runtimeTasks.gaRunsWithLiveOwner();
            orphans = active.stream().filter(run -> !liveOwned.contains(run.id())).toList();
        }
        int interrupted = 0;
        for (GlobalAssistantRun orphan : orphans) {
            try {
                lifecycle.interruptAndTerminalize(orphan.id());
                interrupted++;
            } catch (Exception ignored) {
            }
        }
        int stranded = 0;
        try {
            var pendings = pending.findStrandedPending();
            for (var pt : pendings) {
                try {
                    if (runs.findActiveByThread(pt.threadId()).isPresent()) {
                        continue;
                    }
                    var successor = handoff.tryHandoffAfterCommit(pt.threadId());
                    if (successor.isPresent()) {
                        var s = successor.get();
                        dispatcher.dispatch(s.run().threadId(), s.run().id(), s.message(), s.uiRequest());
                        stranded++;
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        return interrupted + stranded;
    }
}
