package com.specagent.workspace.route;

import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * 文件名:ForkRouteRequest.java
 *
 * 用途:路线分叉请求体。只接受用户可控的元数据(sourceRouteId、label);
 * 由运行时负责的字段(routeId、rootNodeId、tipNodeId、createdFromNodeId、
 * activeRouteId、lifecycleStatus)永远不允许由客户端提供。
 */
public record ForkRouteRequest(
        @NotNull(message = "must be provided")
        UUID sourceRouteId,
        @Size(max = 255, message = "must be at most 255 characters")
        String label) {
}
