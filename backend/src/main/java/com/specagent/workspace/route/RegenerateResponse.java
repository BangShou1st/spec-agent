package com.specagent.workspace.route;

import com.specagent.workspace.node.NodeResponse;
import com.specagent.workspace.route.RouteResponse;

import java.util.UUID;

/**
 * 文件名:RegenerateResponse.java
 *
 * 用途:replacement(重新生成)操作的响应体:历史来源路线、
 * 新的处于 OPEN 状态且被激活的路线,以及 Runtime 接受的 replacement 节点。
 */
public record RegenerateResponse(
        UUID projectId,
        RouteResponse oldRoute,
        RouteResponse replacementRoute,
        NodeResponse replacementNode) {
}
