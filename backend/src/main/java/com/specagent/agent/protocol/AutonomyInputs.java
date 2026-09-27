package com.specagent.agent.protocol;

/**
 * 文件名:AutonomyInputs.java
 *
 * 用途:自主性(autonomy)策略输入,随请求快照告知 Brain 当前的
 * 权限模式。
 *
 * 约束:Stage A 始终发送 {@code ADVISOR}——Brain 不可能通过该字段
 * 为自己提升权限。
 */
public record AutonomyInputs(String mode) {
}
