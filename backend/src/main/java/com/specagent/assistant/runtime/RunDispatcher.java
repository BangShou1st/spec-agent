package com.specagent.assistant.runtime;

import com.specagent.assistant.GlobalAssistantErrorCode;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 文件名:RunDispatcher.java
 *
 * 用途:run 异步派发的唯一入口。所有异步 run 执行都汇聚到这里,
 * 让 createRun 与 steer 交接共享同一套"线程池饱和/执行失败"的处理行为。
 */
@Service
public class RunDispatcher {
    private static final Logger log = LoggerFactory.getLogger(RunDispatcher.class);
    private final Executor gaExecutor;

    private final GlobalAssistantRunLifecycleService lifecycle;
    private final com.specagent.assistant.conversation.GlobalAssistantRunRepository runs;
    private final GaExecutionCoordinator coordinator;
    @org.springframework.beans.factory.annotation.Autowired
    void validateEngine(@org.springframework.beans.factory.annotation.Value("${spec.global-assistant.engine:langchain-ga.v1}") String engine) {
        if (!"langchain-ga.v1".equals(engine))
            throw new IllegalArgumentException("Global Assistant only supports langchain-ga.v1; java-legacy.v1 has been removed");
    }

    public RunDispatcher(Executor gaExecutor, GaExecutionCoordinator coordinator,
            GlobalAssistantRunLifecycleService lifecycle,
            com.specagent.assistant.conversation.GlobalAssistantRunRepository runs) {
        this.gaExecutor = gaExecutor;
        this.coordinator = coordinator;
        this.lifecycle = lifecycle;
        this.runs = runs;
    }

    public void dispatch(UUID threadId, UUID runId, String message, GaHostContext.UiRequest uiRequest) {
        try {
            gaExecutor.execute(() -> executeSafely(threadId, runId, message, uiRequest));
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            log.warn("Global assistant executor saturated, failing queued run: runId={}", runId);
            lifecycle.failWithAssistant(threadId, runId, "The assistant is busy right now, please try again.",
                    GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED, "Executor saturated");
        }
    }

    private void executeSafely(UUID threadId, UUID runId, String message, GaHostContext.UiRequest uiRequest) {
        try {
            coordinator.executeRun(threadId, runId, message, uiRequest);
        } catch (Exception ex) {
            // 这里必须打完整堆栈:这个 catch 是终态化的最后一道网,
            // 缺了它,运行时 bug(比如工具结果之后的 NPE)就会不可见——
            // 用户只看到通用失败,日志里只剩一个类名。
            log.warn("Global assistant run failed unexpectedly: runId={} error={}",
                    runId, ex.getClass().getSimpleName(), ex);
            try {
                var run = runs.findById(runId);
                if (run.isPresent() && run.get().status().isActive()) {
                    lifecycle.failWithAssistant(threadId, runId, "I couldn't complete that step.",
                            GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED,
                            "Unexpected execution failure: " + ex.getClass().getSimpleName());
                }
            } catch (Exception terminalEx) {
                log.warn("Global assistant run terminalization failed: runId={} error={}",
                        runId, terminalEx.getClass().getSimpleName());
            }
        }
    }
}
