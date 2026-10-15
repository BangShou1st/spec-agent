package com.specagent.access;

/**
 * 文件名:Capability.java
 *
 * 用途:确定性能力集合(第二阶段 §5)。角色只映射到能力,能力再被判定使用。
 *
 * 关键区分:
 * <ul>
 *   <li>{@link #PROJECT_WRITE} 与 {@link #PROJECT_READ} 分开,Agent 的读任务
 *       与写任务分别校验——不把"能聊天"映射为"能执行所有工具"。</li>
 *   <li>{@link #PROJECT_MANAGE} 覆盖成员管理、删除项目、改变连接绑定,以及
 *       <b>项目重命名</b>(已冻结决定:EDITOR 不允许重命名)。</li>
 *   <li>{@link #CREDENTIAL_USE} 与 {@link #CREDENTIAL_MANAGE} 分开:具有凭证
 *       管理权也<b>不</b>意味着可以执行任意目标项目操作。</li>
 * </ul>
 */
public enum Capability {
    /** 组织成员、组织配置与共享凭证管理。 */
    ORG_MANAGE,
    /** 转移组织所有权、删除组织。仅最后一个 OWNER 撤销自身时被禁止。 */
    ORG_OWNER,
    /** 读取 Graph、Answer、规格、事件、导出。 */
    PROJECT_READ,
    /** 回答、修改画布、切路线、undo/redo、创建写入 Run。 */
    PROJECT_WRITE,
    /** 管理项目成员、删除项目、改变连接绑定、重命名项目。 */
    PROJECT_MANAGE,
    /** 使用已批准的项目工具连接(按工具能力)。 */
    CREDENTIAL_USE,
    /** 组织共享凭证的增删改与轮换。 */
    CREDENTIAL_MANAGE,
    /** GA 私有会话的属主能力。 */
    THREAD_OWNER
}
