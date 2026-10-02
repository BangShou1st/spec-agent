package com.specagent.assistant.runtime;

import com.specagent.assistant.runtime.GlobalAssistantContextBuilder;
import com.specagent.assistant.GlobalAssistantErrorCode;
import com.specagent.assistant.runtime.GlobalAssistantRunLifecycleService;
import com.specagent.assistant.runtime.GlobalAssistantRuntime;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
    private final ObjectProvider<GlobalAssistantRuntime> runtime;
    private final GlobalAssistantRunLifecycleService lifecycle;
    private final com.specagent.assistant.conversation.GlobalAssistantRunRepository runs;
    private ObjectProvider<GaExecutionCoordinator> coordinator;
    private String engine="java-legacy.v1";

    @org.springframework.beans.factory.annotation.Autowired
    void configureEngine(ObjectProvider<GaExecutionCoordinator> coordinator,
            @org.springframework.beans.factory.annotation.Value("${spec.global-assistant.engine:java-legacy.v1}") String engine) {
        if(!java.util.Set.of("java-legacy.v1","langchain-ga.v1").contains(engine))
            throw new IllegalArgumentException("Unknown Global Assistant engine");
        this.coordinator=coordinator; this.engine=engine;
    }

    public RunDispatcher(Executor gaExecutor, ObjectProvider<GlobalAssistantRuntime> runtime,
            GlobalAssistantRunLifecycleService lifecycle,
            com.specagent.assistant.conversation.GlobalAssistantRunRepository runs) {
        this.gaExecutor = gaExecutor;
        this.runtime = runtime;
        this.lifecycle = lifecycle;
        this.runs = runs;
    }

    public void dispatch(UUID threadId, UUID runId, String message, GlobalAssistantContextBuilder.UiRequest uiRequest) {
        try {
            gaExecutor.execute(() -> executeSafely(threadId, runId, message, uiRequest));
        } catch (java.util.concurrent.RejectedExecutionException ex) {
            log.warn("Global assistant executor saturated, failing queued run: runId={}", runId);
            lifecycle.failWithAssistant(threadId, runId, "The assistant is busy right now, please try again.",
                    GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED, "Executor saturated");
        }
    }

    private void executeSafely(UUID threadId, UUID runId, String message, GlobalAssistantContextBuilder.UiRequest uiRequest) {
        try {
            if("langchain-ga.v1".equals(engine)) coordinator.getObject().executeRun(threadId,runId,message,uiRequest);
            else runtime.getObject().executeRun(threadId, runId, message, uiRequest);
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
