package com.specagent.runtime.execution;

/**
 * 文件名:TaskOwnershipLostException.java
 *
 * 用途:受保护写入发现本执行器已不再拥有该任务时的统一拒绝信号。
 *
 * <p>语义(design §6.3):调用方必须把当前事务整体回滚——业务产物与
 * checkpoint 在同一事务,接管一旦完成,旧执行器的一切迟到写入都落空,
 * 绝不提交结果、绝不重复创建 Answer/Patch。这是"迟到写入拒绝"的唯一
 * 异常类型,便于上层把"所有权丢失"与"业务失败"严格区分。
 */
public class TaskOwnershipLostException extends IllegalStateException {

    public TaskOwnershipLostException(String message) {
        super(message);
    }
}
