package com.specagent.agent.protocol;

import java.util.UUID;

/**
 * 文件名:NodeView.java
 *
 * 用途:决策引擎视角下的图谱节点视图。
 *
 * 约束:kind 是稳定的外层分类(KNOWLEDGE / INTERACTION / RESOURCE /
 * ARTIFACT);子类型与具体 payload 存放在 body/content 中,绝不通过
 * 新增动作家族来表达。
 */
public record NodeView(UUID id, NodeBodyView body, String kind) {
}
