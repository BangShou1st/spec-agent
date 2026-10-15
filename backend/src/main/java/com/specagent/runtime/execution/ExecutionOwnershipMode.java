package com.specagent.runtime.execution;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 文件名:ExecutionOwnershipMode.java
 *
 * 用途:把"所有权粒度"这一个事实集中到一处,供仓储层决定写入守卫的形式
 * (design §5.1、§6.3、§10)。
 *
 * <p>为什么需要它:旧的受保护写入在条件 UPDATE 里内联比较
 * {@code executor_ownership} 的全局代次。多 Worker 模式下那个全局代次不再是
 * 事实来源——权威是 {@code runtime_tasks} 的行锁与 token。若守卫继续比较全局
 * 代次,要么永远失败(全局代次与任务代次无关),要么需要把任务代次硬塞进全局
 * 语义(错误抽象)。
 *
 * <ul>
 *   <li>{@link #taskLevel()} 为 true:守卫只保留"Run 未终态 + owner_epoch
 *       不晚于本次执行代次"。真正的互斥由 {@link TaskWriteGuard} 对
 *       {@code runtime_tasks} 行取排他锁完成,且该锁在同一事务内、先于业务行
 *       锁,因此条件 UPDATE 不再需要跨表子查询。</li>
 *   <li>{@link #taskLevel()} 为 false:保持既有单执行器协议(全局代次比较),
 *       行为与历史完全一致。</li>
 * </ul>
 */
@Component
public class ExecutionOwnershipMode {

    private final boolean taskLevel;

    public ExecutionOwnershipMode(
            @Value("${spec.agent.execution.multi-worker-enabled:false}") boolean taskLevel) {
        this.taskLevel = taskLevel;
    }

    /** 是否由任务级 token + 行锁承担所有权互斥。 */
    public boolean taskLevel() {
        return taskLevel;
    }
}
