package com.specagent.workspace.route;

import com.specagent.workspace.node.RouteTipPort;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:RouteTipPortAdapter.java
 *
 * 用途:节点侧 {@link RouteTipPort} 在 {@link RouteRepository} 之上的
 * 薄实现。自身不添加任何逻辑:tip/root 变更就是既有的
 * {@code updateTipAndRoot} 语句,读取就是既有的 {@code findById}。
 * 它唯一的职责是保持端口的依赖方向,使 {@code NodeService} 永远不
 * import route 包。
 */
@Component
public class RouteTipPortAdapter implements RouteTipPort {

    private final RouteRepository routeRepository;

    public RouteTipPortAdapter(RouteRepository routeRepository) {
        this.routeRepository = routeRepository;
    }

    @Override
    public RouteTip findTip(UUID routeId) {
        Route route = routeRepository.findById(routeId)
                .orElseThrow(() -> new IllegalArgumentException("Route not found: " + routeId));
        return new RouteTip(route.rootNodeId(), route.tipNodeId());
    }

    @Override
    public void advanceTipAndRoot(UUID routeId, UUID tipNodeId, UUID rootNodeId, Instant at) {
        routeRepository.updateTipAndRoot(routeId, tipNodeId, rootNodeId, at);
    }
}
