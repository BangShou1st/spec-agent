package com.specagent.assistant.runtime;
import com.specagent.assistant.conversation.GlobalAssistantRun;
import com.specagent.assistant.conversation.GlobalAssistantRunRepository;
import java.util.List;
import org.springframework.stereotype.Service;
/**
 * 文件名:GlobalAssistantRunRecoveryService.java
 *
 * 用途:单实例 V1 执行器的启动期孤儿恢复。已持久化的 CREATED/RUNNING
 * run 如果属于已死进程,会被诚实地质化为失败:不自动重放工具,
 * 也不重试持久化副作用。待处理的 steer 恢复委托给 TurnHandoffService,
 * 确保被搁置的 steer 依然恰好交接一次。
 */
@Service
public class GlobalAssistantRunRecoveryService {
    private final GlobalAssistantRunRepository runs;
    private final GlobalAssistantRunLifecycleService lifecycle;
    private final com.specagent.assistant.conversation.PendingTurnRepository pending;
    private final com.specagent.assistant.runtime.TurnHandoffService handoff;
    private final com.specagent.assistant.runtime.RunDispatcher dispatcher;
    public GlobalAssistantRunRecoveryService(GlobalAssistantRunRepository runs,
            GlobalAssistantRunLifecycleService lifecycle,
            com.specagent.assistant.conversation.PendingTurnRepository pending,
            com.specagent.assistant.runtime.TurnHandoffService handoff,
            com.specagent.assistant.runtime.RunDispatcher dispatcher) {
        this.runs = runs;
        this.lifecycle = lifecycle;
        this.pending = pending;
        this.handoff = handoff;
        this.dispatcher = dispatcher;
    }
    public int recoverOrphans() {
        List<GlobalAssistantRun> orphans = runs.findActiveRuns();
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
