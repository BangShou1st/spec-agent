package com.specagent.workspace.route;

import java.util.UUID;

/**
 * 文件名:RouteGraphSupportPort.java
 *
 * 用途:路线相关图支持的写侧端口:provenance 校验,以及路线生命周期
 * 命令必须记录的 user-actor 图操作日志条目。
 *
 * 路线域需要图操作日志与路线 provenance 不变量检查,而图域又依赖路线
 * 状态——此端口把 route -&gt; graph 的写边反转,保持这对包无循环。
 * 由 {@code com.specagent.workspace.graph.RouteGraphSupportAdapter} 实现,
 * 内部只是对既有图服务的薄委托。
 */
public interface RouteGraphSupportPort {

    /** 校验路线的 provenance 链条是否完整。 */
    void validateRouteProvenance(UUID routeId);

    /**
     * 为一条路线命令追加一条 user-actor 图操作日志。
     *
     * @param kind        路线操作类型(映射为图日志类型)
     * @param relatedIds  相关的路线 id(分支命令还包括产出的节点 id)
     * @param before      操作前负载属性(值可以是字符串或布尔)
     * @param after       操作后负载属性
     */
    void appendRouteOperation(UUID projectId, RouteOperationKind kind,
                              java.util.List<UUID> relatedIds,
                              java.util.Map<String, Object> before,
                              java.util.Map<String, Object> after);
}
