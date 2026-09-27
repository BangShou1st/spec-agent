package com.specagent.workspace.route;

import java.util.UUID;

/**
 * 文件名:RouteInheritedAnswer.java
 *
 * 用途:分支路线前缀中冻结的一条不可变 Answer 引用。分支路线不复制
 * 答案内容,只记录"该节点上的答案来自哪条路线的哪条 Answer",
 * 保证历史读取与来源路线隔离。
 */
public record RouteInheritedAnswer(
        UUID branchRouteId,
        int ordinal,
        UUID nodeId,
        UUID answerId,
        UUID ownerRouteId) {
}
