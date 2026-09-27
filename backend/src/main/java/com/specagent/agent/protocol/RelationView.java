package com.specagent.agent.protocol;

import java.util.UUID;

/**
 * 文件名:RelationView.java
 *
 * 用途:线上(wire)传输的一条保留方向的语义关系,镜像持久化的
 * {@code ContextRelation}:源节点、目标节点与关系类型码。
 * 决策引擎根据存储的端点判断方向。
 */
public record RelationView(UUID sourceNodeId, UUID targetNodeId, String relationType) {
}
