package com.specagent.workspace.project;

import java.time.Instant;
import java.util.UUID;

/**
 * 文件名:Project.java
 *
 * 用途:需求探索工作区(项目)聚合。{@code activeRouteId} 表示当前的
 * 工作焦点;它与路线的生命周期状态不是一回事,不存在 {@code active}
 * 这样的路线状态。
 */
public class Project {

    private final UUID id;
    private final String title;
    private final UUID activeRouteId;
    private final UUID defaultProfileId;
    private final Instant createdAt;
    private final Instant updatedAt;

    public Project(UUID id,
                   String title,
                   UUID activeRouteId,
                   UUID defaultProfileId,
                   Instant createdAt,
                   Instant updatedAt) {
        this.id = id;
        this.title = title;
        this.activeRouteId = activeRouteId;
        this.defaultProfileId = defaultProfileId;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID id() {
        return id;
    }

    public String title() {
        return title;
    }

    public UUID activeRouteId() {
        return activeRouteId;
    }

    public UUID defaultProfileId() {
        return defaultProfileId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }
}
