package com.specagent.workspace.node;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:RouteTipPort.java
 *
 * 用途:node 包通过此端口推进节点所属路线的 tip(以及 root)。
 *
 * tip 推进是路线侧拥有的不变量,但触发它的写入方是节点创建。
 * 在这里声明接缝——而不是让 {@link NodeService} 直接依赖
 * {@code route.RouteRepository}——保证依赖单向({@code route -> node},
 * 因为 {@code RouteService} 会组合节点创建),从而消除
 * {@code node <-> route} 的包循环。实现位于 route 包,并原样委托给
 * 既有的 repository 语句。
 */
public interface RouteTipPort {

    /** tip 推进规则所需的最小路线身份。 */
    record RouteTip(UUID rootNodeId, UUID tipNodeId) {
    }

    /**
     * 读取路线当前的 root/tip 指针。
     *
     * @throws IllegalArgumentException 当路线不存在时
     */
    RouteTip findTip(UUID routeId);

    /** 推进路线 tip(root 仍为空时一并设置根节点)。 */
    void advanceTipAndRoot(UUID routeId, UUID tipNodeId, UUID rootNodeId, Instant at);
}
