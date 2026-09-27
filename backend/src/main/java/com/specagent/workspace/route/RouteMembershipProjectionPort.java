package com.specagent.workspace.route;

import java.util.Collection;
import java.util.UUID;

/**
 * 文件名:RouteMembershipProjectionPort.java
 *
 * 用途:路线成员关系变化后刷新派生投影的窄写端口。契约由 route 包
 * 持有,可重建的投影实现由 retrieval 侧提供,避免包间耦合。
 */
public interface RouteMembershipProjectionPort {

    /**
     * 为给定的路线 lineage 根及其派生后代刷新节点/资源 provenance。
     * 实现不得重建整个项目,也不得执行 embedding provider 相关工作。
     */
    void refreshRouteAffectedSources(UUID projectId,
                                     UUID routeId,
                                     Collection<UUID> lineageRootNodeIds);

    /** 在 attach/detach 变更后刷新精确的 Node/Resource 来源。 */
    void refreshNodeRouteProvenance(UUID projectId, Collection<UUID> nodeIds);
}
