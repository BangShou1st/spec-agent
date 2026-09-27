package com.specagent.agent.snapshot;

import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:RunAttributionLookupPort.java
 *
 * 用途:snapshot 投影内部使用的窄接口读取端口,按 run id 查询该 run 的
 * 路由/输入节点归属。
 *
 * 协作:端口由消费者(snapshot 包)定义、由 run 流水线实现,使 snapshot
 * 包不必依赖 {@code agent.runtime}:构建器只需要 run 的路由/节点归属用于
 * 能力观察可见性判断,而不需要 run 仓库本身。
 */
public interface RunAttributionLookupPort {

    /** 查询归属,结果承载于 {@link CapabilityObservationVisibility.RunAttribution}。 */
    Optional<CapabilityObservationVisibility.RunAttribution> attributionOf(UUID runId);
}
