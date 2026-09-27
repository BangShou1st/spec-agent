package com.specagent.agent.protocol;

import java.util.UUID;

/**
 * 文件名:RouteContextView.java
 *
 * 用途:构建冻结快照时所基于的路线(route)与读取上下文——
 * 路线 id、当前 tip 节点 id 以及展示标签。
 */
public record RouteContextView(UUID routeId, UUID tipNodeId, String label) {
}
