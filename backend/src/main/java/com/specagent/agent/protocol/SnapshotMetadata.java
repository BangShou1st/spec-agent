package com.specagent.agent.protocol;

/**
 * 文件名:SnapshotMetadata.java
 *
 * 用途:冻结快照携带的低权限工作区元数据(如项目标题)。
 *
 * 约束:{@code projectTitle} 仅作展示上下文,决策引擎绝不能把它
 * 提升为 objective、requirement 或 scope。
 */
public record SnapshotMetadata(String projectTitle) {
}
