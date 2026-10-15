package com.specagent.agent.runtime;

import com.specagent.runtime.execution.RuntimeExecutionProtocol;
import com.specagent.runtime.execution.RuntimeTask;
import com.specagent.runtime.execution.RuntimeTaskFamily;
import com.specagent.runtime.execution.RuntimeTaskRepository;
import com.specagent.runtime.execution.TaskScopeResolver;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:WorkspaceTaskScopeResolver.java
 *
 * 用途:画布任务的执行范围解析(design §4.1):{@code project:{projectId}}。
 * 第一阶段同项目串行、不同项目并行。
 *
 * <p>范围<b>只</b>从数据库里的 Run 行推导,绝不读取请求参数或模型输出;
 * 项目归属查不到时返回空,由调度层按 fail-closed 处理(不认领)。
 *
 * <p>项目活动路线、undo/redo 与恢复存在共享状态,当前不足以安全承诺同项目
 * 不同路线并行——因此范围粒度是项目而不是路线。
 */
@Component
public class WorkspaceTaskScopeResolver implements TaskScopeResolver {

    private final RuntimeTaskRepository tasks;

    public WorkspaceTaskScopeResolver(RuntimeTaskRepository tasks) {
        this.tasks = tasks;
    }

    @Override
    public RuntimeTaskFamily family() {
        return RuntimeTaskFamily.WORKSPACE;
    }

    @Override
    public Optional<String> resolveScope(RuntimeTask task) {
        UUID runId = task.workspaceRunId();
        if (runId == null) {
            return Optional.empty();
        }
        return tasks.projectIdOfWorkspaceRun(runId)
                .map(RuntimeExecutionProtocol::projectScope);
    }
}
