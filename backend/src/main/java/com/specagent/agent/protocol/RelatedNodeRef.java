package com.specagent.agent.protocol;

import java.util.UUID;

/**
 * 文件名:RelatedNodeRef.java
 *
 * 用途:有界 1 跳语义上下文中的一条相关规范节点引用,带有显式出处:
 * 关系类型、相对锚点的方向(锚点是关系源时为 {@code OUTGOING},
 * 锚点是目标时为 {@code INCOMING}),以及相关节点本身的投影
 * {@link NodeView}/{@link NodeBodyView}——让模型读到真实的正文内容,
 * 而不是只有不透明的 id。
 *
 * 约束:对称关系在写入时已做规范化,因此 INCOMING 方向只表示存储的
 * 端点顺序把锚点放在了目标一侧。相关节点永远不属于 lineage。
 */
public record RelatedNodeRef(UUID nodeId, String relationType, String direction, NodeView node) {
}
