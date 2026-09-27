package com.specagent.workspace.route;

import jakarta.validation.constraints.Size;

/**
 * 文件名:StartRouteFromNodeRequest.java
 *
 * 用途:"从游离节点启动路线"的请求体。只接受用户可控的元数据;
 * 由运行时负责的字段(routeId、rootNodeId、tipNodeId、activeRouteId、
 * lifecycleStatus)永远不允许由客户端提供。
 */
public record StartRouteFromNodeRequest(
        @Size(max = 255, message = "must be at most 255 characters")
        String label) {
}
