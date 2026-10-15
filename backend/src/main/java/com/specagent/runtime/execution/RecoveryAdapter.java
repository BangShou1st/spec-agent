package com.specagent.runtime.execution;

/**
 * 文件名:RecoveryAdapter.java
 *
 * 用途:让某个 task_type 有资格自动恢复的核验端口(design §7)。
 *
 * <p><b>只有提供本适配器且通过故障测试的 task_type 才可以自动恢复</b>,
 * 其余一律使用租约过期后的诚实失败。适配器必须只访问持久化数据,不得发起
 * 任何外部调用(那正是它要判定的对象),也不得改变业务状态——它的唯一职责
 * 是回答"现在接管安全吗"。
 *
 * <p>典型判定依据:
 * <ul>
 *   <li>步骤账本中已完成步骤的输入 hash 与协议版本;</li>
 *   <li>不可变事实(Answer/Patch/Spec)是否已存在及其身份;</li>
 *   <li>外部有副作用的工具调用是否留下 IN_FLIGHT/UNKNOWN 记录;</li>
 *   <li>checkpoint 与调用账本是否一致。</li>
 * </ul>
 */
public interface RecoveryAdapter {

    /** 本适配器负责的 taskType。 */
    String taskType();

    /** 核验接管是否安全;必须只读,且绝不猜测。 */
    RecoveryVerdict assess(RuntimeTask task);
}
