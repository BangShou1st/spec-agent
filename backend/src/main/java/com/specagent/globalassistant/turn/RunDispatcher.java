package com.specagent.globalassistant.turn;

import com.specagent.globalassistant.context.GlobalAssistantContextBuilder;
import com.specagent.globalassistant.runtime.GlobalAssistantErrorCode;
import com.specagent.globalassistant.runtime.GlobalAssistantRunLifecycleService;
import com.specagent.globalassistant.runtime.GlobalAssistantRuntime;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Single dispatch owner. All async run execution funnels here so
 * createRun and steer-handoff share saturation/failure behavior.
 */
@Service
public class RunDispatcher {
    private static final Logger log = LoggerFactory.getLogger(RunDispatcher.class);
    private final Executor gaExecutor;
    private final ObjectProvider<GlobalAssistantRuntime> runtime;
    private final GlobalAssistantRunLifecycleService lifecycle;
    private final com.specagent.globalassistant.conversation.GlobalAssistantRunRepository runs;

    public RunDispatcher(Executor gaExecutor, ObjectProvider<GlobalAssistantRuntime> runtime,
            GlobalAssistantRunLifecycleService lifecycle,
            com.specagent.globalassistant.conversation.GlobalAssistantRunRepository runs) {
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
            runtime.getObject().executeRun(threadId, runId, message, uiRequest);
        } catch (Exception ex) {
            log.warn("Global assistant run failed unexpectedly: runId={} error={}", runId, ex.getClass().getSimpleName());
            try {
                var run = runs.findById(runId);
                if (run.isPresent() && run.get().status().isActive()) {
                    lifecycle.failWithAssistant(threadId, runId, "I couldn't complete that step.",
                            GlobalAssistantErrorCode.TOOL_EXECUTION_FAILED, "Unexpected execution failure");
                }
            } catch (Exception terminalEx) {
                log.warn("Global assistant run terminalization failed: runId={} error={}", runId, terminalEx.getClass().getSimpleName());
            }
        }
    }
}
