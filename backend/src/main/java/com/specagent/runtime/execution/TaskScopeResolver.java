package com.specagent.runtime.execution;

import java.util.Optional;

/**
 * 文件名:TaskScopeResolver.java
 *
 * 用途:把任务解析为它需要的执行范围集合(design §5.2)。范围<b>只能</b>
 * 由 Java 根据持久化命令生成,模型与客户端输入绝不能指定范围。
 *
 * <p>由业务包实现:画布族返回 {@code project:{projectId}}(同项目串行、
 * 不同项目并行);全局助手返回 {@code ga-thread:{threadId}}(同会话至多
 * 一个活跃 Run)。
 */
public interface TaskScopeResolver {

    /** 本解析器处理的任务族。 */
    RuntimeTaskFamily family();

    /**
     * 解析该任务需要的独占范围键集合;无法解析时返回空(调用方按
     * fail-closed 处理,绝不猜测范围)。
     */
    Optional<String> resolveScope(RuntimeTask task);
}
