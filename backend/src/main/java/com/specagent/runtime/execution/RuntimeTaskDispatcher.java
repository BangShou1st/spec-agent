package com.specagent.runtime.execution;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 文件名:RuntimeTaskDispatcher.java
 *
 * 用途:按 task_type 找到业务执行适配器(design §10)。找不到适配器时
 * fail-closed:任务不执行,由调度层按"不可执行"处理——绝不静默成功。
 */
@Component
public class RuntimeTaskDispatcher {

    private final List<TaskExecutionAdapter> adapters;

    public RuntimeTaskDispatcher(List<TaskExecutionAdapter> adapters) {
        this.adapters = adapters;
    }

    /** 查找处理该 taskType 的适配器;重复注册是装配错误。 */
    public Optional<TaskExecutionAdapter> adapterFor(String taskType) {
        TaskExecutionAdapter found = null;
        for (TaskExecutionAdapter adapter : adapters) {
            if (adapter.taskTypes().contains(taskType)) {
                if (found != null) {
                    throw new IllegalStateException(
                            "Multiple task execution adapters registered for task type "
                                    + taskType + ": " + found.getClass().getSimpleName()
                                    + " and " + adapter.getClass().getSimpleName());
                }
                found = adapter;
            }
        }
        return Optional.ofNullable(found);
    }

    /** 已注册的适配器数量(装配自检用)。 */
    public int adapterCount() {
        return adapters.size();
    }

    /** 全部已注册的 taskType(健康检查/诊断用)。 */
    public Set<String> registeredTaskTypes() {
        Set<String> types = new java.util.TreeSet<>();
        for (TaskExecutionAdapter adapter : adapters) {
            types.addAll(adapter.taskTypes());
        }
        return types;
    }
}
