package com.specagent.workspace.context;

import java.util.UUID;

/**
 * 文件名:ContextRelation.java
 *
 * 用途:冻结上下文中的一条保持方向的 ACTIVE 语义关系,用于节点查询的有界
 * 一跳语义上下文。关系按持久化原样保存(source/target/type),让决策引擎能
 * 看到方向。对称关系类型在写入时已由
 * {@code GraphInvariantValidator.endpointsCanonicalized} 规范化,因此
 * RELATED_TO / CONFLICTS_WITH 的存储方向可能是 {@code (minId, maxId)};本记录
 * 对该存储方向原样保留、不做改写。
 */
public record ContextRelation(UUID sourceNodeId, UUID targetNodeId, String relationType) {

    public ContextRelation {
        if (sourceNodeId == null || targetNodeId == null) {
            throw new IllegalArgumentException("A context relation must have both endpoints");
        }
        if (relationType == null || relationType.isBlank()) {
            throw new IllegalArgumentException("A context relation must have a relation type");
        }
    }
}
