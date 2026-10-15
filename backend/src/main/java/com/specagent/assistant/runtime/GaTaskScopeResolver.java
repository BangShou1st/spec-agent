package com.specagent.assistant.runtime;

import com.specagent.runtime.execution.RuntimeExecutionProtocol;
import com.specagent.runtime.execution.RuntimeTask;
import com.specagent.runtime.execution.RuntimeTaskFamily;
import com.specagent.runtime.execution.RuntimeTaskRepository;
import com.specagent.runtime.execution.TaskScopeResolver;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 文件名:GaTaskScopeResolver.java
 *
 * 用途:全局助手任务的执行范围解析(design §4.2):{@code ga-thread:{threadId}}。
 *
 * <p>同一会话至多一个活跃 Run——这既是既有的产品不变量(数据库唯一索引
 * {@code uq_ga_runs_single_active} 兜底),也是这里的独占范围。因此并发会话可以
 * 并行,而同一会话保留 steer 的交接语义。
 *
 * <p>范围只从数据库里的 Run 行推导,绝不读取请求参数。
 */
@Component
public class GaTaskScopeResolver implements TaskScopeResolver {

    private final RuntimeTaskRepository tasks;

    public GaTaskScopeResolver(RuntimeTaskRepository tasks) {
        this.tasks = tasks;
    }

    @Override
    public RuntimeTaskFamily family() {
        return RuntimeTaskFamily.GLOBAL_ASSISTANT;
    }

    @Override
    public Optional<String> resolveScope(RuntimeTask task) {
        if (task.gaRunId() == null) {
            return Optional.empty();
        }
        return tasks.threadIdOfGaRun(task.gaRunId())
                .map(RuntimeExecutionProtocol::threadScope);
    }
}
