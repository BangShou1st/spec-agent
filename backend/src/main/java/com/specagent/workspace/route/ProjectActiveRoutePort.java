package com.specagent.workspace.route;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * 文件名:ProjectActiveRoutePort.java
 *
 * 用途:项目行锁与活跃路线指针的写侧端口。路线生命周期命令必须按
 * 项目行串行化,并维护 {@code Project.activeRouteId};但路线域若直接依赖
 * 项目 repository(而项目创建又依赖路线创建),会闭合 project &lt;-&gt; route
 * 的包循环。此端口把 route -&gt; project 这条边反转:路线域只面向"锁、
 * 指针更新、活跃路线读取"编程。由项目侧的 {@code ProjectRepository} 实现。
 */
public interface ProjectActiveRoutePort {

    /** 阻塞直到持有该项目的行锁(FOR UPDATE)。 */
    void lockProject(UUID projectId);

    /** 更新活跃路线指针,并顺带刷新项目的 updated_at。 */
    void updateActiveRoute(UUID projectId, UUID routeId, Instant updatedAt);

    /**
     * 当前活跃路线指针;没有活跃路线时返回 empty。
     * 项目不存在属于硬错误,抛出 {@code IllegalArgumentException}。
     */
    Optional<UUID> findActiveRouteId(UUID projectId);
}
